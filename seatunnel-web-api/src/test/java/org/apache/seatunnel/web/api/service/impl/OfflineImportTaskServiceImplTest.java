package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.api.offlineimport.ExecutionLogBuffer;
import org.apache.seatunnel.web.api.offlineimport.OfflineImportProperties;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.FileResourceService;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.FileResourceDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskDao;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportOptions;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.enums.OfflineImportType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfflineImportTaskServiceImplTest {

    private static final Long DATASOURCE_ID = 22979298326688L;
    private static final Long FILE_RESOURCE_ID = 1001L;
    private static final Integer USER_ID = 7;

    @TempDir
    Path tempDir;

    private OfflineImportTaskDao offlineImportTaskDao;
    private DataSourceDao dataSourceDao;
    private FileResourceService fileResourceService;
    private FileResourceDao fileResourceDao;
    private FileResourceStorageProvider storageProvider;
    private CurrentUserProvider currentUserProvider;
    private OfflineImportTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        offlineImportTaskDao = mock(OfflineImportTaskDao.class);
        dataSourceDao = mock(DataSourceDao.class);
        fileResourceService = mock(FileResourceService.class);
        fileResourceDao = mock(FileResourceDao.class);
        storageProvider = mock(FileResourceStorageProvider.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        service = new OfflineImportTaskServiceImpl();
        ReflectionTestUtils.setField(service, "offlineImportTaskDao", offlineImportTaskDao);
        ReflectionTestUtils.setField(service, "dataSourceDao", dataSourceDao);
        ReflectionTestUtils.setField(service, "fileResourceService", fileResourceService);
        ReflectionTestUtils.setField(service, "fileResourceDao", fileResourceDao);
        ReflectionTestUtils.setField(service, "storageProvider", storageProvider);
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

    private OfflineImportTask task(Long id, String status, Integer ownerId) {
        OfflineImportTask task = new OfflineImportTask();
        task.setId(id);
        task.setStatus(status);
        task.setCreateUserId(ownerId);
        return task;
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
    void decodesChineseGbKSqlWithoutReplacingCharacters() {
        String script = "INSERT INTO t VALUES ('中文');";

        assertEquals(script, OfflineImportTaskServiceImpl.decode(
                script.getBytes(Charset.forName("GBK")), "GBK"));
    }

    @Test
    void rejectsMalformedSqlBytesAndPreservesUtf8BomHandling() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> OfflineImportTaskServiceImpl.decode(new byte[] {(byte) 0xC4, (byte) 0xE3}, "UTF-8"));
        assertTrue(exception.getMessage().contains("编码不匹配"));
        assertEquals("SELECT 1;", OfflineImportTaskServiceImpl.decode(
                new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '1', ';'},
                StandardCharsets.UTF_8.name()));
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

    @Test
    void pageIsScopedToCurrentOwner() {
        OfflineImportTaskQueryDTO query = new OfflineImportTaskQueryDTO();
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<OfflineImportTask> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        when(offlineImportTaskDao.queryPage(query, USER_ID)).thenReturn(page);
        when(dataSourceDao.queryByIds(any())).thenReturn(List.of());

        service.page(query);

        verify(offlineImportTaskDao).queryPage(query, USER_ID);
    }

    @Test
    void getHidesTaskOwnedByAnotherUser() {
        when(offlineImportTaskDao.queryById(1L)).thenReturn(task(1L, "PENDING", USER_ID + 1));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.get(1L));

        assertEquals(Status.OFFLINE_IMPORT_TASK_NOT_EXIST.getCode(), exception.getCode());
    }

    @Test
    void deleteHidesTaskOwnedByAnotherUser() {
        when(offlineImportTaskDao.queryById(1L)).thenReturn(task(1L, "PENDING", USER_ID + 1));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.delete(1L));

        assertEquals(Status.OFFLINE_IMPORT_TASK_NOT_EXIST.getCode(), exception.getCode());
        verify(offlineImportTaskDao, never()).deleteIfNotRunning(any(), any());
    }

    @Test
    void deleteFailsIfTaskStartsRunningAfterTheInitialRead() {
        when(offlineImportTaskDao.queryById(1L)).thenReturn(task(1L, "QUEUED", USER_ID));
        when(offlineImportTaskDao.deleteIfNotRunning(1L, USER_ID)).thenReturn(false);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.delete(1L));

        assertEquals(Status.OFFLINE_IMPORT_TASK_STATE_ERROR.getCode(), exception.getCode());
        verify(offlineImportTaskDao).deleteIfNotRunning(1L, USER_ID);
    }

    @Test
    void deleteRejectsRunningTask() {
        when(offlineImportTaskDao.queryById(1L)).thenReturn(task(1L, "RUNNING", USER_ID));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.delete(1L));
        assertEquals(Status.OFFLINE_IMPORT_TASK_STATE_ERROR.getCode(), exception.getCode());
    }

    @Test
    void executeRejectsWhileAlreadyQueuedOrRunning() {
        when(offlineImportTaskDao.queryById(1L)).thenReturn(task(1L, "RUNNING", USER_ID));
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(1L), eq(USER_ID), any())).thenReturn(false);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.execute(1L));
        assertEquals(Status.OFFLINE_IMPORT_TASK_STATE_ERROR.getCode(), exception.getCode());
    }

    @Test
    void executeHidesTaskOwnedByAnotherUser() {
        when(offlineImportTaskDao.queryById(1L)).thenReturn(task(1L, "PENDING", USER_ID + 1));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.execute(1L));

        assertEquals(Status.OFFLINE_IMPORT_TASK_NOT_EXIST.getCode(), exception.getCode());
        verify(offlineImportTaskDao, never()).markQueuedIfExecutable(any(), any(), any());
    }

    @Test
    void executeMarksTaskFailedWhenExecutorRejectsSubmission() {
        when(offlineImportTaskDao.queryById(3L)).thenReturn(task(3L, "PENDING", USER_ID));
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(3L), eq(USER_ID), any())).thenReturn(true);
        when(offlineImportTaskDao.failQueued(eq(3L), eq(USER_ID), any(), any())).thenReturn(true);
        service.setExecutorForTest(command -> {
            throw new RejectedExecutionException("full");
        });

        ServiceException exception = assertThrows(ServiceException.class, () -> service.execute(3L));

        assertEquals(Status.OFFLINE_IMPORT_TASK_STATE_ERROR.getCode(), exception.getCode());
        verify(offlineImportTaskDao).failQueued(
                eq(3L), eq(USER_ID), eq("任务执行器当前不可用，请稍后重试"), any());
    }

    @Test
    void executeWorkerMarksVanishedTaskFailed() {
        when(offlineImportTaskDao.queryById(9L))
                .thenReturn(task(9L, "PENDING", USER_ID), (OfflineImportTask) null);
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(9L), eq(USER_ID), any())).thenReturn(true);
        when(offlineImportTaskDao.markRunningFromQueued(eq(9L), eq(USER_ID), any())).thenReturn(true);
        service.setExecutorForTest(Runnable::run);

        service.execute(9L);

        ArgumentCaptor<OfflineImportTask> captor = ArgumentCaptor.forClass(OfflineImportTask.class);
        verify(offlineImportTaskDao).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getErrorMessage());
    }

    @Test
    void executeWorkerSkipsTaskDeletedWhileQueued() {
        when(offlineImportTaskDao.queryById(11L)).thenReturn(task(11L, "PENDING", USER_ID));
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(11L), eq(USER_ID), any())).thenReturn(true);
        when(offlineImportTaskDao.markRunningFromQueued(eq(11L), eq(USER_ID), any())).thenReturn(false);
        service.setExecutorForTest(Runnable::run);

        service.execute(11L);

        verify(offlineImportTaskDao, never()).updateById(any(OfflineImportTask.class));
    }

    @Test
    void workerRejectsFileResourceOwnedByAnotherUser() throws Exception {
        when(offlineImportTaskDao.queryById(13L))
                .thenReturn(taskForSql(13L), taskForSql(13L));
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(13L), eq(USER_ID), any())).thenReturn(true);
        when(offlineImportTaskDao.markRunningFromQueued(eq(13L), eq(USER_ID), any())).thenReturn(true);
        when(dataSourceDao.queryById(DATASOURCE_ID)).thenReturn(kingbaseDataSource());
        FileResource resource = readyFileResource(USER_ID + 1);
        when(fileResourceDao.queryById(FILE_RESOURCE_ID)).thenReturn(resource);
        service.setExecutorForTest(Runnable::run);

        service.execute(13L);

        ArgumentCaptor<OfflineImportTask> captor = ArgumentCaptor.forClass(OfflineImportTask.class);
        verify(offlineImportTaskDao).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertTrue(captor.getValue().getErrorMessage().contains("不可用"));
        verify(storageProvider, never()).download(any(), any(OutputStream.class));
    }

    @Test
    void workerRejectsFileResourceThatIsNotReady() throws Exception {
        when(offlineImportTaskDao.queryById(14L))
                .thenReturn(taskForSql(14L), taskForSql(14L));
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(14L), eq(USER_ID), any())).thenReturn(true);
        when(offlineImportTaskDao.markRunningFromQueued(eq(14L), eq(USER_ID), any())).thenReturn(true);
        when(dataSourceDao.queryById(DATASOURCE_ID)).thenReturn(kingbaseDataSource());
        FileResource resource = readyFileResource(USER_ID);
        resource.setStatus("DELETED");
        when(fileResourceDao.queryById(FILE_RESOURCE_ID)).thenReturn(resource);
        service.setExecutorForTest(Runnable::run);

        service.execute(14L);

        ArgumentCaptor<OfflineImportTask> captor = ArgumentCaptor.forClass(OfflineImportTask.class);
        verify(offlineImportTaskDao).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertTrue(captor.getValue().getErrorMessage().contains("不可用"));
        verify(storageProvider, never()).download(any(), any(OutputStream.class));
    }

    @Test
    void workerRejectsDatasourceTypeChangedAfterTaskCreation() throws Exception {
        when(offlineImportTaskDao.queryById(15L))
                .thenReturn(taskForSql(15L), taskForSql(15L));
        when(offlineImportTaskDao.markQueuedIfExecutable(eq(15L), eq(USER_ID), any())).thenReturn(true);
        when(offlineImportTaskDao.markRunningFromQueued(eq(15L), eq(USER_ID), any())).thenReturn(true);
        DataSource mysql = kingbaseDataSource();
        mysql.setDbType(DbType.MYSQL);
        when(dataSourceDao.queryById(DATASOURCE_ID)).thenReturn(mysql);
        service.setExecutorForTest(Runnable::run);

        service.execute(15L);

        ArgumentCaptor<OfflineImportTask> captor = ArgumentCaptor.forClass(OfflineImportTask.class);
        verify(offlineImportTaskDao).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertTrue(captor.getValue().getErrorMessage().contains("类型已变更"));
        verify(fileResourceDao, never()).queryById(FILE_RESOURCE_ID);
    }

    @Test
    void rejectsKingbaseFilterWithNoMatchingSchemaBeforeOpeningTarget() throws Exception {
        Path restoreTool = tempDir.resolve("sys_restore-list");
        Files.writeString(restoreTool,
                "#!/bin/sh\ncase \"$*\" in *--schema=missing_schema*) "
                        + "printf '%s\\n' 'no matching archive entries' >&2; exit 1;; esac\n"
                        + "printf '%s\\n' '3359; 1259 16444 TABLE public another_table owner'\n");
        Files.setPosixFilePermissions(restoreTool, PosixFilePermissions.fromString("rwx------"));
        OfflineImportProperties properties = new OfflineImportProperties();
        properties.setWorkDir(tempDir.resolve("scratch").toString());
        properties.setKingbaseSysRestorePath(restoreTool.toString());
        properties.setProcessTimeoutMinutes(1);
        ReflectionTestUtils.setField(service, "offlineImportProperties", properties);

        FileResource resource = readyFileResource(USER_ID);
        resource.setObjectKey("offline-import-test-object");
        when(fileResourceDao.queryById(FILE_RESOURCE_ID)).thenReturn(resource);
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(1)).write(new byte[] {1, 2, 3});
            return null;
        }).when(storageProvider).download(eq("offline-import-test-object"), any(OutputStream.class));

        OfflineImportTask task = task(50L, "RUNNING", USER_ID);
        task.setFileResourceId(FILE_RESOURCE_ID);
        task.setFileName("source.dmp");
        task.setCreateUserId(USER_ID);
        task.setTargetSchema("missing_schema");

        String failure = ReflectionTestUtils.invokeMethod(
                service, "runDmpImport", task, OfflineImportType.KINGBASE_DMP,
                kingbaseDataSource(), new OfflineImportOptions(), new ExecutionLogBuffer(8_192));

        assertTrue(failure.contains("原厂预检失败"));
        assertTrue(failure.contains("源 Schema 未匹配"));
        verify(storageProvider).download(eq("offline-import-test-object"), any(OutputStream.class));
    }

    @Test
    void acceptsKingbaseStrictSchemaPreflightSuccessAndContinuesRestore() throws Exception {
        Path restoreTool = tempDir.resolve("sys_restore-matched");
        Path calls = tempDir.resolve("restore-tool-calls.txt");
        Files.writeString(restoreTool,
                "#!/bin/sh\n"
                        + "case \"$*\" in *--list*) printf '%s\\n' 'TABLE DATA src_schema sample owner'; "
                        + "echo list >> '" + calls + "';; *) echo restore >> '" + calls + "';; esac\n"
                        + "exit 0\n");
        Files.setPosixFilePermissions(restoreTool, PosixFilePermissions.fromString("rwx------"));
        OfflineImportProperties properties = new OfflineImportProperties();
        properties.setWorkDir(tempDir.resolve("scratch-matched").toString());
        properties.setKingbaseSysRestorePath(restoreTool.toString());
        properties.setProcessTimeoutMinutes(1);
        ReflectionTestUtils.setField(service, "offlineImportProperties", properties);

        FileResource resource = readyFileResource(USER_ID);
        resource.setObjectKey("offline-import-test-object");
        when(fileResourceDao.queryById(FILE_RESOURCE_ID)).thenReturn(resource);
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(1)).write(new byte[] {1, 2, 3});
            return null;
        }).when(storageProvider).download(eq("offline-import-test-object"), any(OutputStream.class));

        OfflineImportTask task = task(51L, "RUNNING", USER_ID);
        task.setFileResourceId(FILE_RESOURCE_ID);
        task.setFileName("source.dmp");
        task.setCreateUserId(USER_ID);
        task.setTargetSchema("target_schema");
        OfflineImportOptions options = new OfflineImportOptions();
        options.setSourceSchema("src_schema");

        DataSource dataSource = kingbaseDataSource();
        BaseConnectionParam connectionParam = mock(BaseConnectionParam.class);
        when(connectionParam.getHost()).thenReturn("127.0.0.1");
        when(connectionParam.getPort()).thenReturn("54321");
        when(connectionParam.getDatabase()).thenReturn("kb");
        when(connectionParam.getUser()).thenReturn("offline_import_test");
        when(connectionParam.getPassword()).thenReturn("test-secret");
        DataSourceProcessor processor = mock(DataSourceProcessor.class);
        JdbcConnectionProvider connectionProvider = mock(JdbcConnectionProvider.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(processor.getConnectionManager()).thenReturn(connectionProvider);
        when(connectionProvider.getConnection(connectionParam)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        try (MockedStatic<DataSourceUtils> dataSourceUtils = mockStatic(DataSourceUtils.class)) {
            dataSourceUtils.when(() -> DataSourceUtils.buildConnectionParams(
                    DbType.KINGBASE, dataSource.getConnectionParams())).thenReturn(connectionParam);
            dataSourceUtils.when(() -> DataSourceUtils.getDatasourceProcessor(DbType.KINGBASE))
                    .thenReturn(processor);

            String failure = ReflectionTestUtils.invokeMethod(
                    service, "runDmpImport", task, OfflineImportType.KINGBASE_DMP,
                    dataSource, options, new ExecutionLogBuffer(8_192));

            assertNull(failure);
        }

        assertEquals(List.of("list", "restore"), Files.readAllLines(calls));
        verify(statement).setQueryTimeout(300);
        verify(statement).execute("CREATE SCHEMA IF NOT EXISTS \"target_schema\"");
    }

    @Test
    void readsOnlyTheConfiguredTailOfLargeNativeToolLogs() throws Exception {
        Path toolLog = tempDir.resolve("large-tool.log");
        Files.writeString(toolLog, "x".repeat(1_000_000) + "FINAL_MARKER", StandardCharsets.UTF_8);

        String tail = ReflectionTestUtils.invokeMethod(
                service, "readTail", toolLog, 32, StandardCharsets.UTF_8);

        assertEquals(32, tail.length());
        assertTrue(tail.endsWith("FINAL_MARKER"));
    }

    private OfflineImportTask taskForSql(Long id) {
        OfflineImportTask task = task(id, "PENDING", USER_ID);
        task.setName("sql-import");
        task.setImportType("KINGBASE_SQL");
        task.setFileResourceId(FILE_RESOURCE_ID);
        task.setFileName("dump.sql");
        task.setTargetDatasourceId(DATASOURCE_ID);
        task.setOptionsJson("{}");
        return task;
    }

    private FileResource readyFileResource(Integer ownerId) {
        FileResource resource = new FileResource();
        resource.setOwnerId(ownerId);
        resource.setResourceType("FILE");
        resource.setStatus("READY");
        resource.setSize(10L);
        resource.setObjectKey("object-key");
        return resource;
    }
}
