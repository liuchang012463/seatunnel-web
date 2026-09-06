package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.job.LakeJobRelationBridgeService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.apache.seatunnel.web.api.service.StreamingJobInstanceService;
import org.apache.seatunnel.web.api.service.StreamingJobMetricsService;
import org.apache.seatunnel.web.api.service.application.JobScheduleApplicationService;
import org.apache.seatunnel.web.api.service.application.LakeExactSingleProjectionApplicationService;
import org.apache.seatunnel.web.api.service.cdc.CdcServerIdAllocationService;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.core.job.assembler.BatchJobDefinitionAssembler;
import org.apache.seatunnel.web.core.job.assembler.StreamingJobDefinitionAssembler;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.core.job.registry.JobDefinitionModeHandlerRegistry;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.repository.JobDefinitionContentDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionContentDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchScriptJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.StreamingJobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeProjectionSaveWiringTest {

    @Mock private JobDefinitionModeHandlerRegistry handlerRegistry;
    @Mock private JobDefinitionModeHandler handler;
    @Mock private JobDefinitionDao jobDefinitionDao;
    @Mock private JobDefinitionContentDao jobDefinitionContentDao;
    @Mock private BatchJobDefinitionAssembler batchAssembler;
    @Mock private JobScheduleApplicationService scheduleApplicationService;
    @Mock private CdcServerIdAllocationService cdcServerIdAllocationService;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private IncrementalBatchService incrementalBatchService;
    @Mock private LakeJobRelationBridgeService relationBridge;
    @Mock private LakeExactSingleProjectionApplicationService projectionService;
    @Mock private StreamingJobDefinitionDao streamingJobDefinitionDao;
    @Mock private StreamingJobDefinitionContentDao streamingContentDao;
    @Mock private StreamingJobDefinitionAssembler streamingAssembler;
    @Mock private StreamingJobInstanceService streamingJobInstanceService;
    @Mock private StreamingJobMetricsService streamingJobMetricsService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private BatchJobDefinitionServiceImpl batchService;
    @InjectMocks private StreamingJobDefinitionServiceImpl streamingService;

    private LakeExactSingleProjectionApplicationService.PreparedProjection prepared;

    @BeforeEach
    void setUp() {
        prepared = new LakeExactSingleProjectionApplicationService.PreparedProjection(null, null);
        when(currentUserProvider.getCurrentUserId()).thenReturn(42);
        when(handlerRegistry.getHandler(any(JobDefinitionMode.class))).thenReturn(handler);
        when(handler.analyze(any())).thenReturn(new JobDefinitionAnalysisResult());
        when(handler.serializeDefinition(any())).thenReturn("{}");
    }

    @Test
    void batchPreparePrecedesDefinitionWriteAndApplyPrecedesRelationForAutoProjection() {
        BatchGuideSingleJobSaveCommand command = batchSingle(101L);
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setId(101L);
        when(projectionService.prepare(command)).thenReturn(prepared);
        when(jobDefinitionDao.queryById(101L)).thenReturn(null);
        when(batchAssembler.create(command, new JobDefinitionAnalysisResult())).thenReturn(entity);
        when(jobDefinitionDao.saveOrUpdate(entity)).thenReturn(true);
        when(jobDefinitionContentDao.save(any())).thenReturn(1);

        batchService.doSaveOrUpdate(command);

        InOrder order = inOrder(projectionService, jobDefinitionDao,
                jobDefinitionContentDao, relationBridge);
        order.verify(projectionService).prepare(command);
        order.verify(jobDefinitionDao).saveOrUpdate(entity);
        order.verify(jobDefinitionContentDao).save(any());
        order.verify(projectionService).applyPrepared(prepared, 42);
        order.verify(relationBridge).syncRelationAfterJobSave(
                command, 101L, 1, org.apache.seatunnel.web.common.enums.LakeJobRuntimeType.BATCH);
    }

    @Test
    void batchUnmanagedProjectionIsAppliedAfterContent() {
        BatchGuideSingleJobSaveCommand command = batchSingle(102L);
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setId(102L);
        when(projectionService.prepare(command)).thenReturn(prepared);
        when(jobDefinitionDao.queryById(102L)).thenReturn(null);
        when(batchAssembler.create(command, new JobDefinitionAnalysisResult())).thenReturn(entity);
        when(jobDefinitionDao.saveOrUpdate(entity)).thenReturn(true);
        when(jobDefinitionContentDao.save(any())).thenReturn(1);

        batchService.doSaveOrUpdate(command);

        verify(projectionService).applyPrepared(prepared, 42);
        verify(relationBridge).syncRelationAfterJobSave(
                eq(command), eq(102L), eq(1), eq(org.apache.seatunnel.web.common.enums.LakeJobRuntimeType.BATCH));
    }

    @Test
    void streamingPrepareAndApplyUseTheSameCapturedUserBeforeRelation() {
        StreamingGuideSingleJobSaveCommand command = streamingSingle(201L);
        StreamingJobDefinitionEntity entity = new StreamingJobDefinitionEntity();
        entity.setId(201L);
        when(projectionService.prepare(command)).thenReturn(prepared);
        when(streamingJobDefinitionDao.queryById(201L)).thenReturn(null);
        when(streamingAssembler.create(command, new JobDefinitionAnalysisResult())).thenReturn(entity);
        streamingService.doSaveOrUpdate(command);

        InOrder order = inOrder(projectionService, streamingJobDefinitionDao,
                streamingContentDao, relationBridge);
        order.verify(projectionService).prepare(command);
        order.verify(streamingJobDefinitionDao).saveOrUpdate(entity);
        order.verify(streamingContentDao).save(any());
        order.verify(projectionService).applyPrepared(prepared, 42);
        order.verify(relationBridge).syncRelationAfterJobSave(
                command, 201L, 1, org.apache.seatunnel.web.common.enums.LakeJobRuntimeType.STREAMING);
    }

    @Test
    void ordinaryPreparedNullSkipsApplyAndDoesNotBlockRelation() {
        BatchScriptJobSaveCommand command = new BatchScriptJobSaveCommand();
        command.setId(301L);
        command.setBasic(basic("script"));
        command.setEnv(new BatchJobEnvConfig());
        when(projectionService.prepare(command)).thenReturn(null);
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setId(301L);
        when(jobDefinitionDao.queryById(301L)).thenReturn(null);
        when(batchAssembler.create(command, new JobDefinitionAnalysisResult())).thenReturn(entity);
        when(jobDefinitionDao.saveOrUpdate(entity)).thenReturn(true);
        when(jobDefinitionContentDao.save(any())).thenReturn(1);

        batchService.doSaveOrUpdate(command);

        verify(projectionService).prepare(command);
        verify(projectionService, never()).applyPrepared(any(), any());
        verify(relationBridge).syncRelationAfterJobSave(
                command, 301L, 1, org.apache.seatunnel.web.common.enums.LakeJobRuntimeType.BATCH);
    }

    @Test
    void projectionApplyFailurePreventsRelationAndKeepsSaveTransactional()
            throws NoSuchMethodException {
        BatchGuideSingleJobSaveCommand command = batchSingle(401L);
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setId(401L);
        when(projectionService.prepare(command)).thenReturn(prepared);
        when(jobDefinitionDao.queryById(401L)).thenReturn(null);
        when(batchAssembler.create(command, new JobDefinitionAnalysisResult())).thenReturn(entity);
        when(jobDefinitionDao.saveOrUpdate(entity)).thenReturn(true);
        when(jobDefinitionContentDao.save(any())).thenReturn(1);
        when(projectionService.applyPrepared(prepared, 42)).thenThrow(
                new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, "projection conflict"));

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> batchService.doSaveOrUpdate(command));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(relationBridge, never()).syncRelationAfterJobSave(
                any(), any(), any(Integer.class), any());
        TransactionAttributeSource attributes = new AnnotationTransactionAttributeSource();
        TransactionAttribute attribute = attributes.getTransactionAttribute(
                BatchJobDefinitionServiceImpl.class.getMethod(
                        "saveOrUpdate", BatchGuideSingleJobSaveCommand.class),
                BatchJobDefinitionServiceImpl.class);
        org.junit.jupiter.api.Assertions.assertTrue(attribute.rollbackOn(new Exception()));
    }

    private BatchGuideSingleJobSaveCommand batchSingle(long id) {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setId(id);
        command.setBasic(basic("batch-" + id));
        command.setEnv(new BatchJobEnvConfig());
        return command;
    }

    private StreamingGuideSingleJobSaveCommand streamingSingle(long id) {
        StreamingGuideSingleJobSaveCommand command = new StreamingGuideSingleJobSaveCommand();
        command.setId(id);
        command.setBasic(basic("streaming-" + id));
        command.setEnv(new StreamingJobEnvConfig());
        return command;
    }

    private JobBasicConfig basic(String name) {
        JobBasicConfig basic = new JobBasicConfig();
        basic.setJobName(name);
        return basic;
    }
}
