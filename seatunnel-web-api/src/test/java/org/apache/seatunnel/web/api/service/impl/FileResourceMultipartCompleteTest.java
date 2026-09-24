package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.fileresource.FileResourceMqNotifier;
import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.api.fileresource.storage.StorageObjectMetadata;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.apache.seatunnel.web.dao.entity.FileUploadRecord;
import org.apache.seatunnel.web.dao.repository.FileResourceDao;
import org.apache.seatunnel.web.dao.repository.FileUploadRecordDao;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartCompleteRequestDTO;
import org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartPartETagDTO;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileResourceMultipartCompleteTest {

    private static final Integer OWNER_ID = 7;
    private static final Long RECORD_ID = 1001L;
    private static final long FILE_SIZE = 1024L;
    private static final long PART_SIZE = 64L * 1024L * 1024L;

    private FileResourceDao fileResourceDao;
    private FileUploadRecordDao fileUploadRecordDao;
    private FileUploadRecordPersistenceService persistenceService;
    private CurrentUserProvider currentUserProvider;
    private FileResourceStorageProvider storageProvider;
    private FileResourceMqNotifier mqNotifier;
    private FileResourceServiceImpl service;

    @BeforeEach
    void setUp() {
        fileResourceDao = mock(FileResourceDao.class);
        fileUploadRecordDao = mock(FileUploadRecordDao.class);
        persistenceService = mock(FileUploadRecordPersistenceService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        storageProvider = mock(FileResourceStorageProvider.class);
        mqNotifier = mock(FileResourceMqNotifier.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.apache.seatunnel.web.api.fileresource.FileResourceReferenceChecker> checkers =
                mock(ObjectProvider.class);

        when(currentUserProvider.getCurrentUserId()).thenReturn(OWNER_ID);
        when(storageProvider.providerType()).thenReturn("S3_COMPATIBLE");
        when(storageProvider.bucket()).thenReturn("demo-bucket");
        when(storageProvider.objectKey(anyString())).thenAnswer(invocation ->
                "seatunnel-web-resource/" + OWNER_ID + invocation.getArgument(0));
        when(checkers.orderedStream()).thenReturn(java.util.stream.Stream.empty());
        when(fileResourceDao.queryActiveByOwnerId(OWNER_ID)).thenReturn(Collections.emptyList());
        when(fileUploadRecordDao.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(fileResourceDao.queryByOwnerAndLogicalPath(eq(OWNER_ID), anyString())).thenReturn(null);
        doAnswer(invocation -> {
            FileResource resource = invocation.getArgument(0);
            if (resource.getId() == null) {
                resource.setId(9001L);
            }
            return 1;
        }).when(fileResourceDao).insert(any(FileResource.class));
        when(fileUploadRecordDao.updateById(any(FileUploadRecord.class))).thenReturn(true);

        service = new FileResourceServiceImpl();
        ReflectionTestUtils.setField(service, "fileResourceDao", fileResourceDao);
        ReflectionTestUtils.setField(service, "fileUploadRecordDao", fileUploadRecordDao);
        ReflectionTestUtils.setField(service, "fileUploadRecordPersistenceService", persistenceService);
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        ReflectionTestUtils.setField(service, "storageProvider", storageProvider);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "referenceCheckers", checkers);
        ReflectionTestUtils.setField(service, "fileResourceMqNotifier", mqNotifier);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void completePublishesMqOnlyAfterCommit() {
        FileUploadRecord record = uploadingRecord("/docs/a.txt");
        when(fileUploadRecordDao.queryByIdForUpdate(RECORD_ID)).thenReturn(record);
        when(storageProvider.completeMultipartUpload(anyString(), anyString(), anyList()))
                .thenReturn("\"mp-etag\"");
        when(storageProvider.head(record.getObjectKey()))
                .thenReturn(new StorageObjectMetadata(FILE_SIZE, "text/plain", "\"obj-etag\""));

        TransactionSynchronizationManager.initSynchronization();
        FileResourceVO vo = service.completeMultipartUpload(RECORD_ID, completeRequest(1, "\"part-1\""));

        assertEquals("/docs/a.txt", vo.getLogicalPath());
        assertEquals(FILE_SIZE, vo.getSize());
        verify(mqNotifier, never()).notifyUploaded(any(), anyLong());

        List<TransactionSynchronization> syncs =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        assertEquals(1, syncs.size());
        syncs.get(0).afterCommit();

        ArgumentCaptor<FileResource> resourceCaptor = ArgumentCaptor.forClass(FileResource.class);
        verify(mqNotifier).notifyUploaded(resourceCaptor.capture(), eq(RECORD_ID));
        assertEquals(FILE_SIZE, resourceCaptor.getValue().getSize());
        assertEquals("SUCCESS", record.getStatus());
    }

    @Test
    void completeRejectsSizeMismatchAndDoesNotNotify() {
        FileUploadRecord record = uploadingRecord("/docs/b.txt");
        when(fileUploadRecordDao.queryByIdForUpdate(RECORD_ID)).thenReturn(record);
        when(storageProvider.completeMultipartUpload(anyString(), anyString(), anyList()))
                .thenReturn("\"mp-etag\"");
        when(storageProvider.head(record.getObjectKey()))
                .thenReturn(new StorageObjectMetadata(FILE_SIZE * 2, "text/plain", "\"obj-etag\""));

        TransactionSynchronizationManager.initSynchronization();
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.completeMultipartUpload(RECORD_ID, completeRequest(1, "\"part-1\"")));

        assertTrue(error.getMessage() != null && error.getMessage().contains("大小与申报不一致"),
                () -> "unexpected message: " + error.getMessage());
        verify(storageProvider).delete(record.getObjectKey());
        verify(persistenceService).markFailed(eq(record), anyString());
        verify(mqNotifier, never()).notifyUploaded(any(), anyLong());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void completeIdempotentSuccessSkipsMq() {
        FileUploadRecord record = uploadingRecord("/docs/c.txt");
        record.setStatus("SUCCESS");
        FileResource existing = new FileResource();
        existing.setId(55L);
        existing.setOwnerId(OWNER_ID);
        existing.setLogicalPath("/docs/c.txt");
        existing.setObjectKey(record.getObjectKey());
        existing.setName("c.txt");
        existing.setResourceType("FILE");
        existing.setSize(FILE_SIZE);
        existing.setStatus("READY");
        when(fileUploadRecordDao.queryByIdForUpdate(RECORD_ID)).thenReturn(record);
        when(fileResourceDao.queryByOwnerAndLogicalPath(OWNER_ID, "/docs/c.txt")).thenReturn(existing);

        FileResourceVO vo = service.completeMultipartUpload(RECORD_ID, completeRequest(1, "\"part-1\""));

        assertEquals(55L, vo.getId());
        verify(storageProvider, never()).completeMultipartUpload(anyString(), anyString(), anyList());
        verify(mqNotifier, never()).notifyUploaded(any(), anyLong());
    }

    @Test
    void initiateRejectsDescendantPathConflict() {
        FileUploadRecord inProgress = uploadingRecord("/a/b/c.txt");
        inProgress.setId(2002L);
        when(fileUploadRecordDao.selectList(any(Wrapper.class))).thenReturn(List.of(inProgress));

        org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartUploadRequestDTO request =
                new org.apache.seatunnel.web.spi.bean.dto.FileResourceMultipartUploadRequestDTO();
        request.setPath("/a");
        request.setRelativePath("b");
        request.setSize(FILE_SIZE);
        request.setContentType("text/plain");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.initiateMultipartUpload(request));
        assertTrue(error.getMessage() != null && error.getMessage().contains("正在上传"),
                () -> "unexpected message: " + error.getMessage());
        verify(storageProvider, never()).initiateMultipartUpload(anyString(), anyString());
    }

    @Test
    void expiredUploadingSessionIsFailedBeforeComplete() {
        FileUploadRecord record = uploadingRecord("/docs/old.txt");
        Date stale = new Date(System.currentTimeMillis() - 3L * 60L * 60L * 1000L);
        record.setCreateTime(stale);
        record.setUpdateTime(stale);
        when(fileUploadRecordDao.queryByIdForUpdate(RECORD_ID)).thenReturn(record);

        assertThrows(ServiceException.class,
                () -> service.completeMultipartUpload(RECORD_ID, completeRequest(1, "\"part-1\"")));
        verify(storageProvider).abortMultipartUpload(record.getObjectKey(), record.getMultipartUploadId());
        verify(persistenceService).markFailed(eq(record), anyString());
        verify(storageProvider, never()).completeMultipartUpload(anyString(), anyString(), anyList());
        verify(mqNotifier, never()).notifyUploaded(any(), anyLong());
    }

    private static FileUploadRecord uploadingRecord(String logicalPath) {
        FileUploadRecord record = new FileUploadRecord();
        record.setId(RECORD_ID);
        record.setOwnerId(OWNER_ID);
        record.setProviderType("S3_COMPATIBLE");
        record.setBucket("demo-bucket");
        record.setTargetPath("/");
        record.setLogicalPath(logicalPath);
        record.setObjectKey("seatunnel-web-resource/" + OWNER_ID + logicalPath);
        record.setContentType("text/plain");
        record.setMultipartUploadId("upload-xyz");
        record.setPartSize(PART_SIZE);
        record.setTotalFiles(1);
        record.setTotalSize(FILE_SIZE);
        record.setStatus("UPLOADING");
        record.setCreateTime(new Date());
        record.setUpdateTime(new Date());
        return record;
    }

    private static FileResourceMultipartCompleteRequestDTO completeRequest(int partNumber, String etag) {
        FileResourceMultipartPartETagDTO part = new FileResourceMultipartPartETagDTO();
        part.setPartNumber(partNumber);
        part.setEtag(etag);
        FileResourceMultipartCompleteRequestDTO request = new FileResourceMultipartCompleteRequestDTO();
        request.setParts(List.of(part));
        return request;
    }
}
