package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.offlineimport.OfflineImportProperties;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.FileResourceService;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskDao;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportOptions;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfflineImportTaskServiceImplTest {

    private static final Long DATASOURCE_ID = 22979298326688L;
    private static final Long FILE_RESOURCE_ID = 1001L;
    private static final Integer USER_ID = 7;

    private OfflineImportTaskDao offlineImportTaskDao;
    private DataSourceDao dataSourceDao;
    private FileResourceService fileResourceService;
    private CurrentUserProvider currentUserProvider;
    private OfflineImportTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        offlineImportTaskDao = mock(OfflineImportTaskDao.class);
        dataSourceDao = mock(DataSourceDao.class);
        fileResourceService = mock(FileResourceService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        service = new OfflineImportTaskServiceImpl();
        ReflectionTestUtils.setField(service, "offlineImportTaskDao", offlineImportTaskDao);
        ReflectionTestUtils.setField(service, "dataSourceDao", dataSourceDao);
        ReflectionTestUtils.setField(service, "fileResourceService", fileResourceService);
        ReflectionTestUtils.setField(service, "currentUserProvider", currentUserProvider);
        ReflectionTestUtils.setField(service, "offlineImportProperties", new OfflineImportProperties());
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
    }

    private FileResourceVO fileResource(String name, long size) {
        FileResourceVO vo = new FileResourceVO();
        vo.setId(FILE_RESOURCE_ID);
        vo.setName(name);
        vo.setSize(size);
        vo.setResourceType("FILE");
        return vo;
    }

    private DataSource kingbaseDataSource() {
        DataSource dataSource = new DataSource();
        dataSource.setId(DATASOURCE_ID);
        dataSource.setName("kingbase-1");
        dataSource.setDbType(DbType.KINGBASE);
        dataSource.setConnectionParams("{\"url\":\"jdbc:kingbase8://127.0.0.1:54321/kb\"}");
        return dataSource;
    }

    private OfflineImportTaskCreateDTO createDto(String importType, String fileName) {
        OfflineImportTaskCreateDTO dto = new OfflineImportTaskCreateDTO();
        dto.setName("离线导入任务A");
        dto.setImportType(importType);
        dto.setFileResourceId(FILE_RESOURCE_ID);
        dto.setTargetDatasourceId(DATASOURCE_ID);
        dto.setTargetSchema("public");
        when(fileResourceService.get(FILE_RESOURCE_ID)).thenReturn(fileResource(fileName, 100));
        when(dataSourceDao.queryById(DATASOURCE_ID)).thenReturn(kingbaseDataSource());
        return dto;
    }

    @Test
    void createPersistsPendingTaskWithOptionDefaults() {
        OfflineImportTaskVO vo = service.create(createDto("KINGBASE_SQL", "dump.sql"));

        assertEquals("PENDING", vo.getStatus());
        assertEquals("KINGBASE_SQL", vo.getImportType());
        assertEquals("dump.sql", vo.getFileName());
        assertEquals("kingbase-1", vo.getTargetDatasourceName());

        ArgumentCaptor<OfflineImportTask> captor = ArgumentCaptor.forClass(OfflineImportTask.class);
        verify(offlineImportTaskDao).insert(captor.capture());
        OfflineImportTask saved = captor.getValue();
        assertEquals(USER_ID, saved.getCreateUserId());
        assertTrue(saved.getOptionsJson().contains("\"stopOnError\":true"));
        assertTrue(saved.getOptionsJson().contains("\"encoding\":\"UTF-8\""));
    }

    @Test
    void createRejectsExtensionMismatch() {
        OfflineImportTaskCreateDTO dto = createDto("KINGBASE_DMP", "dump.sql");

        ServiceException exception = assertThrows(ServiceException.class, () -> service.create(dto));
        assertEquals(Status.REQUEST_PARAMS_NOT_VALID_ERROR.getCode(), exception.getCode());
    }

    @Test
    void createRejectsDatasourceTypeMismatch() {
        OfflineImportTaskCreateDTO dto = createDto("KINGBASE_SQL", "dump.sql");
        DataSource mysql = kingbaseDataSource();
        mysql.setDbType(DbType.MYSQL);
        // Re-stub after createDto so the mismatched type actually applies.
        when(dataSourceDao.queryById(DATASOURCE_ID)).thenReturn(mysql);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.create(dto));
        assertEquals(Status.REQUEST_PARAMS_NOT_VALID_ERROR.getCode(), exception.getCode());
    }

    @Test
    void createRejectsUnknownImportType() {
        OfflineImportTaskCreateDTO dto = createDto("ORACLE_SQL", "dump.sql");

        ServiceException exception = assertThrows(ServiceException.class, () -> service.create(dto));
        assertEquals(Status.REQUEST_PARAMS_NOT_VALID_ERROR.getCode(), exception.getCode());
    }

    private OfflineImportTask ownedTask(Long id, String status) {
        OfflineImportTask task = new OfflineImportTask();
        task.setId(id);
        task.setStatus(status);
        task.setCreateUserId(USER_ID);
        return task;
    }

    @Test
    void deleteRejectsRunningTask() {
        when(offlineImportTaskDao.queryById(1L)).thenReturn(ownedTask(1L, "RUNNING"));
        when(offlineImportTaskDao.deleteIfNotRunning(1L)).thenReturn(false);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.delete(1L));
        assertEquals(Status.OFFLINE_IMPORT_TASK_STATE_ERROR.getCode(), exception.getCode());
    }

    @Test
    void deleteUsesAtomicConditionalDelete() {
        when(offlineImportTaskDao.queryById(2L)).thenReturn(ownedTask(2L, "PENDING"));
        when(offlineImportTaskDao.deleteIfNotRunning(2L)).thenReturn(true);

        service.delete(2L);

        verify(offlineImportTaskDao).deleteIfNotRunning(2L);
        verify(offlineImportTaskDao, never()).deleteById(any());
    }

    @Test
    void deleteRejectsAnotherUsersTask() {
        OfflineImportTask task = ownedTask(3L, "PENDING");
        task.setCreateUserId(USER_ID + 1);
        when(offlineImportTaskDao.queryById(3L)).thenReturn(task);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.delete(3L));
        assertEquals(Status.OFFLINE_IMPORT_TASK_NOT_EXIST.getCode(), exception.getCode());
        verify(offlineImportTaskDao, never()).deleteIfNotRunning(any());
    }

    @Test
    void executeRejectsWhileAlreadyQueuedOrRunning() {
        when(offlineImportTaskDao.queryById(1L)).thenReturn(ownedTask(1L, "RUNNING"));
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(1L), any())).thenReturn(false);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.execute(1L));
        assertEquals(Status.OFFLINE_IMPORT_TASK_STATE_ERROR.getCode(), exception.getCode());
    }

    @Test
    void executeRejectsAnotherUsersTask() {
        OfflineImportTask task = ownedTask(4L, "PENDING");
        task.setCreateUserId(USER_ID + 1);
        when(offlineImportTaskDao.queryById(4L)).thenReturn(task);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.execute(4L));
        assertEquals(Status.OFFLINE_IMPORT_TASK_NOT_EXIST.getCode(), exception.getCode());
        verify(offlineImportTaskDao, never()).markQueuedIfExecutable(any(), any());
    }

    @Test
    void pageScopesByOwnerAndOmitsExecutionLog() {
        OfflineImportTask task = ownedTask(5L, "SUCCESS");
        task.setTargetDatasourceId(DATASOURCE_ID);
        task.setExecutionLog("large log");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<OfflineImportTask> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(List.of(task));
        when(offlineImportTaskDao.queryPage(any(), eq(USER_ID))).thenReturn(page);

        var result = service.page(new OfflineImportTaskQueryDTO());

        assertEquals(1, result.getData().getBizData().size());
        assertEquals(null, result.getData().getBizData().get(0).getExecutionLog());
        verify(offlineImportTaskDao).queryPage(any(), eq(USER_ID));
    }

    @Test
    void createRejectsOversizedSqlFile() {
        OfflineImportTaskCreateDTO dto = createDto("KINGBASE_SQL", "dump.sql");
        when(fileResourceService.get(FILE_RESOURCE_ID))
                .thenReturn(fileResource("dump.sql", 1024L * 1024 * 1024));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.create(dto));
        assertEquals(Status.REQUEST_PARAMS_NOT_VALID_ERROR.getCode(), exception.getCode());
        verify(offlineImportTaskDao, never()).insert(any());
    }

    @Test
    void createRejectsInvalidEncoding() {
        OfflineImportTaskCreateDTO dto = createDto("KINGBASE_SQL", "dump.sql");
        OfflineImportOptions options = new OfflineImportOptions();
        options.setEncoding("NO-SUCH-CHARSET");
        dto.setOptions(options);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.create(dto));
        assertEquals(Status.REQUEST_PARAMS_NOT_VALID_ERROR.getCode(), exception.getCode());
    }

    @Test
    void createRejectsKingbaseSchemaRemap() {
        OfflineImportTaskCreateDTO dto = createDto("KINGBASE_DMP", "dump.dmp");
        OfflineImportOptions options = new OfflineImportOptions();
        options.setSourceSchema("SRC");
        dto.setOptions(options);
        dto.setTargetSchema("DST");

        ServiceException exception = assertThrows(ServiceException.class, () -> service.create(dto));
        assertEquals(Status.REQUEST_PARAMS_NOT_VALID_ERROR.getCode(), exception.getCode());
    }

    @Test
    void executeWorkerMarksVanishedTaskFailed() {
        when(offlineImportTaskDao.queryById(9L)).thenReturn(ownedTask(9L, "QUEUED"), (OfflineImportTask) null);
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(9L), any())).thenReturn(true);
        when(offlineImportTaskDao.markRunningFromQueued(eq(9L), any())).thenReturn(true);
        service.setExecutorForTest(Runnable::run);

        service.execute(9L);

        verify(offlineImportTaskDao).markTerminatedIfActive(
                eq(9L), eq("FAILED"), any(), any(), any());
    }

    @Test
    void executeWorkerSkipsTaskDeletedWhileQueued() {
        when(offlineImportTaskDao.queryById(11L)).thenReturn(ownedTask(11L, "QUEUED"));
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(11L), any())).thenReturn(true);
        when(offlineImportTaskDao.markRunningFromQueued(eq(11L), any())).thenReturn(false);
        service.setExecutorForTest(Runnable::run);

        service.execute(11L);

        verify(offlineImportTaskDao, never()).markTerminatedIfActive(
                any(), any(), any(), any(), any());
    }
}
