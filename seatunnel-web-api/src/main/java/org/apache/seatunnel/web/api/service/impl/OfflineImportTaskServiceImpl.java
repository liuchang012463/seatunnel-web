package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.api.offlineimport.DmpCommandBuilder;
import org.apache.seatunnel.web.api.offlineimport.DmpProcessRunner;
import org.apache.seatunnel.web.api.offlineimport.ExecutionLogBuffer;
import org.apache.seatunnel.web.api.offlineimport.OfflineImportProperties;
import org.apache.seatunnel.web.api.offlineimport.SqlScriptImportRunner;
import org.apache.seatunnel.web.api.offlineimport.SqlScriptSplitter;
import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.FileResourceService;
import org.apache.seatunnel.web.api.service.OfflineImportTaskService;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.FileResourceDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskDao;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportOptions;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.enums.OfflineImportType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class OfflineImportTaskServiceImpl implements OfflineImportTaskService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private static final int LOG_MAX_CHARS = 64 * 1024;
    private static final long PROGRESS_FLUSH_INTERVAL_MS = 3000;

    @Resource
    private OfflineImportTaskDao offlineImportTaskDao;

    @Resource
    private DataSourceDao dataSourceDao;

    @Resource
    private FileResourceService fileResourceService;

    /**
     * Background workers have no request context, so they bypass the
     * owner-scoped FileResourceService and read storage through the DAO and
     * the storage provider directly.
     */
    @Resource
    private FileResourceDao fileResourceDao;

    @Resource
    private FileResourceStorageProvider storageProvider;

    @Resource
    private CurrentUserProvider currentUserProvider;

    @Resource
    private OfflineImportProperties offlineImportProperties;

    private final ExecutorService defaultExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "offline-import-task-worker");
        thread.setDaemon(true);
        return thread;
    });

    private Executor executor = defaultExecutor;

    /** Lets unit tests run task submissions on the calling thread. */
    void setExecutorForTest(Executor testExecutor) {
        this.executor = testExecutor;
    }

    /** Tasks stuck in RUNNING/QUEUED belong to a previous process; fail them so they can re-run. */
    @PostConstruct
    public void failOrphanedRunningTasks() {
        int failed = offlineImportTaskDao.failAllActive("服务重启导致任务中断，请重新执行");
        if (failed > 0) {
            log.warn("Failed {} offline import tasks orphaned by restart", failed);
        }
    }

    @PreDestroy
    public void shutdown() {
        defaultExecutor.shutdownNow();
    }

    @Override
    public OfflineImportTaskVO create(OfflineImportTaskCreateDTO dto) {
        OfflineImportType importType = OfflineImportType.of(dto.getImportType());
        if (importType == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "importType");
        }
        FileResourceVO resource = fileResourceService.get(dto.getFileResourceId());
        if (!"FILE".equalsIgnoreCase(resource.getResourceType())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "只能选择文件资源");
        }
        String fileName = StringUtils.defaultIfBlank(resource.getName(), "");
        if (!fileName.toLowerCase().endsWith(importType.expectedExtension())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "文件 " + fileName + " 与导入类型不匹配，应为 " + importType.expectedExtension() + " 文件");
        }
        DataSource dataSource = dataSourceDao.queryById(dto.getTargetDatasourceId());
        if (dataSource == null) {
            throw new ServiceException(Status.DATASOURCE_NOT_EXIST);
        }
        if (dataSource.getDbType() != importType.getTargetDbType()) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "目标数据源类型 " + dataSource.getDbType() + " 与导入类型 " + importType.getCode() + " 不匹配");
        }

        OfflineImportTask entity = new OfflineImportTask();
        entity.initInsert();
        entity.setName(dto.getName().trim());
        entity.setDescription(StringUtils.trimToNull(dto.getDescription()));
        entity.setImportType(importType.getCode());
        entity.setFileResourceId(dto.getFileResourceId());
        entity.setFileName(fileName);
        entity.setTargetDatasourceId(dto.getTargetDatasourceId());
        entity.setTargetSchema(StringUtils.trimToNull(dto.getTargetSchema()));
        entity.setOptionsJson(JSONUtils.toJsonString(defaultOptions(dto.getOptions())));
        entity.setStatus(STATUS_PENDING);
        entity.setTotalStatements(0);
        entity.setExecutedStatements(0);
        entity.setFailedStatements(0);
        entity.setCreateUserId(currentUserProvider.getCurrentUserId());
        offlineImportTaskDao.insert(entity);
        return toVO(entity, dataSource.getName());
    }

    private OfflineImportOptions defaultOptions(OfflineImportOptions raw) {
        OfflineImportOptions options = raw == null ? new OfflineImportOptions() : raw;
        if (options.getStopOnError() == null) {
            options.setStopOnError(Boolean.TRUE);
        }
        if (StringUtils.isBlank(options.getEncoding())) {
            options.setEncoding(StandardCharsets.UTF_8.name());
        }
        if (options.getIgnoreErrors() == null) {
            options.setIgnoreErrors(Boolean.TRUE);
        }
        return options;
    }

    @Override
    public PaginationResult<OfflineImportTaskVO> page(OfflineImportTaskQueryDTO query) {
        IPage<OfflineImportTask> page = offlineImportTaskDao.queryPage(query);
        Map<Long, String> dataSourceNames = dataSourceNames(page.getRecords());
        return PaginationResult.buildSuc(
                page.getRecords().stream()
                        .map(entity -> toVO(entity, dataSourceNames.get(entity.getTargetDatasourceId())))
                        .toList(),
                page);
    }

    @Override
    public OfflineImportTaskVO get(Long id) {
        OfflineImportTask task = requireTask(id);
        DataSource dataSource = dataSourceDao.queryById(task.getTargetDatasourceId());
        return toVO(task, dataSource == null ? null : dataSource.getName());
    }

    @Override
    public void delete(Long id) {
        OfflineImportTask task = requireTask(id);
        if (STATUS_RUNNING.equals(task.getStatus())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务正在执行中，不能删除");
        }
        offlineImportTaskDao.deleteById(id);
    }

    @Override
    public void execute(Long id) {
        requireTask(id);
        if (!offlineImportTaskDao.markQueuedIfExecutable(id, new java.util.Date())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务正在排队或执行中");
        }
        executor.execute(() -> {
            try {
                runTask(id);
            } catch (Throwable t) {
                log.error("Offline import task {} crashed", id, t);
                markTerminated(id, STATUS_FAILED, "任务执行异常: " + StringUtils.defaultIfBlank(t.getMessage(), t.getClass().getSimpleName()), null);
            }
        });
    }

    private OfflineImportTask requireTask(Long id) {
        OfflineImportTask task = id == null ? null : offlineImportTaskDao.queryById(id);
        if (task == null) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_NOT_EXIST, String.valueOf(id));
        }
        return task;
    }

    private void runTask(Long id) {
        // QUEUED → RUNNING marks the real start; a task deleted while queued stays deleted.
        if (!offlineImportTaskDao.markRunningFromQueued(id, new java.util.Date())) {
            log.info("Offline import task {} is no longer queued, skip run", id);
            return;
        }
        OfflineImportTask task = requireTask(id);
        OfflineImportType importType = OfflineImportType.of(task.getImportType());
        OfflineImportOptions options = defaultOptions(
                JSONUtils.parseObject(task.getOptionsJson(), OfflineImportOptions.class));
        DataSource dataSource = dataSourceDao.queryById(task.getTargetDatasourceId());
        ExecutionLogBuffer executionLog = new ExecutionLogBuffer(LOG_MAX_CHARS);
        executionLog.line("开始执行离线导入任务: " + task.getName() + "（类型 " + task.getImportType() + "）");

        try {
            if (importType == null || dataSource == null) {
                throw new IllegalStateException(importType == null ? "导入类型无效" : "目标数据源不存在");
            }
            String failure = importType.isSql()
                    ? runSqlImport(task, importType, dataSource, options, executionLog)
                    : runDmpImport(task, importType, dataSource, options, executionLog);
            boolean success = failure == null;
            executionLog.line(success ? "任务执行成功" : "任务执行失败: " + failure);
            markTerminated(id, success ? STATUS_SUCCESS : STATUS_FAILED, failure, executionLog.snapshot());
        } catch (Exception e) {
            log.error("Offline import task {} failed", id, e);
            String failure = "任务执行异常: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            executionLog.line(failure);
            markTerminated(id, STATUS_FAILED, failure, executionLog.snapshot());
        }
    }

    private String runSqlImport(
            OfflineImportTask task,
            OfflineImportType importType,
            DataSource dataSource,
            OfflineImportOptions options,
            ExecutionLogBuffer executionLog) throws Exception {
        FileResource resource = requireWorkerResource(task.getFileResourceId());
        long maxBytes = offlineImportProperties.getSqlMaxFileBytes();
        if (resource.getSize() != null && resource.getSize() > maxBytes) {
            return "SQL 文件超过大小上限 " + maxBytes + " 字节";
        }

        byte[] bytes = readFileResource(resource);
        String text = decode(bytes, options.getEncoding());
        executionLog.line("已读取 SQL 文件 " + task.getFileName()
                + "（" + bytes.length + " 字节，编码 " + options.getEncoding() + "）");

        List<String> statements = SqlScriptSplitter.split(text);
        executionLog.line("解析得到 " + statements.size() + " 条语句");
        updateProgress(task.getId(), statements.size(), 0, 0);

        BaseConnectionParam connectionParam = (BaseConnectionParam) DataSourceUtils.buildConnectionParams(
                dataSource.getDbType(), dataSource.getConnectionParams());
        try (Connection connection = DataSourceUtils.getDatasourceProcessor(dataSource.getDbType())
                .getConnectionManager()
                .getConnection(connectionParam)) {
            SqlScriptImportRunner runner = new SqlScriptImportRunner(connection, executionLog);
            runner.runPrelude(schemaPrelude(dataSource.getDbType(), task.getTargetSchema()));

            AtomicLong lastFlush = new AtomicLong(System.currentTimeMillis());
            int[] counters = new int[2];
            String abort = runner.execute(statements, Boolean.TRUE.equals(options.getStopOnError()),
                    (executed, failed, total) -> {
                        counters[0] = executed;
                        counters[1] = failed;
                        long now = System.currentTimeMillis();
                        if (now - lastFlush.get() >= PROGRESS_FLUSH_INTERVAL_MS) {
                            lastFlush.set(now);
                            updateProgress(task.getId(), total, executed, failed);
                        }
                    });
            updateProgress(task.getId(), statements.size(), counters[0], counters[1]);
            return abort;
        }
    }

    private String runDmpImport(
            OfflineImportTask task,
            OfflineImportType importType,
            DataSource dataSource,
            OfflineImportOptions options,
            ExecutionLogBuffer executionLog) throws Exception {
        String toolPath = importType == OfflineImportType.DAMENG_DMP
                ? offlineImportProperties.getDamengDimpPath()
                : offlineImportProperties.getKingbaseSysRestorePath();
        String toolName = importType == OfflineImportType.DAMENG_DMP ? "dimp" : "sys_restore";
        if (StringUtils.isBlank(toolPath)) {
            return "服务器未配置 " + toolName + " 工具路径，无法执行 DMP 导入"
                    + "（可通过环境变量 SEATUNNEL_WEB_OFFLINE_IMPORT_"
                    + (importType == OfflineImportType.DAMENG_DMP ? "DIMP_PATH" : "SYS_RESTORE_PATH") + " 配置）";
        }

        // dimp resolves DIRECTORY itself, so the scratch dir must be absolute.
        Path workDir = Paths.get(offlineImportProperties.getWorkDir())
                .toAbsolutePath()
                .resolve("task-" + task.getId());
        Files.createDirectories(workDir);
        try {
            String safeName = task.getFileName().replaceAll("[/\\\\]", "_");
            Path dmpFile = workDir.resolve(safeName);
            Path toolLogFile = workDir.resolve(safeName + ".log");
            // Stream straight to disk: DMP backups may be far larger than the heap.
            FileResource dmpResource = requireWorkerResource(task.getFileResourceId());
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(dmpFile)) {
                storageProvider.download(dmpResource.getObjectKey(), output);
            }
            executionLog.line("已下载 DMP 文件 " + task.getFileName() + "，调用 " + toolName + " 导入");

            BaseConnectionParam param = (BaseConnectionParam) DataSourceUtils.buildConnectionParams(
                    dataSource.getDbType(), dataSource.getConnectionParams());
            DmpCommandBuilder.DmpCommand command;
            if (importType == OfflineImportType.DAMENG_DMP) {
                command = DmpCommandBuilder.dameng(
                        toolPath, param.getHost(), param.getPort(), param.getUser(), param.getPassword(),
                        dmpFile, toolLogFile,
                        options.getSourceSchema(), task.getTargetSchema(),
                        Boolean.TRUE.equals(options.getIgnoreErrors()));
            } else {
                command = DmpCommandBuilder.kingbase(
                        toolPath, param.getHost(), param.getPort(), param.getDatabase(),
                        param.getUser(), param.getPassword(), dmpFile, task.getTargetSchema());
            }
            executionLog.line("执行命令: " + DmpCommandBuilder.mask(command, param.getPassword()));

            DmpProcessRunner.ProcessResult result = new DmpProcessRunner().run(
                    command, command.environment(), workDir,
                    offlineImportProperties.getProcessTimeoutMinutes(), executionLog);
            appendToolLogFile(toolLogFile, executionLog);
            return result.success()
                    ? null
                    : toolName + " 退出码 " + result.exitCode() + "，详见执行日志"
                            + (result.timedOut() ? "（已超时强制终止）" : "");
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void appendToolLogFile(Path toolLogFile, ExecutionLogBuffer executionLog) {
        try {
            if (!Files.exists(toolLogFile)) {
                return;
            }
            String content = Files.readString(toolLogFile, StandardCharsets.UTF_8);
            if (StringUtils.isNotBlank(content)) {
                executionLog.line("工具日志尾部:");
                executionLog.raw(tail(content, 8 * 1024));
            }
        } catch (IOException e) {
            log.warn("Read tool log file failed: {}", toolLogFile, e);
        }
    }

    private String tail(String content, int maxChars) {
        return content.length() <= maxChars ? content : content.substring(content.length() - maxChars);
    }

    private void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort scratch cleanup.
                }
            });
        } catch (IOException e) {
            log.warn("Clean offline import work dir failed: {}", dir, e);
        }
    }

    private List<String> schemaPrelude(DbType dbType, String targetSchema) {
        List<String> prelude = new ArrayList<>();
        if (StringUtils.isBlank(targetSchema)) {
            return prelude;
        }
        if (dbType == DbType.KINGBASE) {
            prelude.add("SET search_path TO \"" + targetSchema.replace("\"", "\"\"") + "\"");
        } else if (dbType == DbType.DAMENG) {
            prelude.add("SET SCHEMA \"" + targetSchema.replace("\"", "\"\"") + "\"");
        }
        return prelude;
    }

    private String decode(byte[] bytes, String encoding) {
        Charset charset;
        try {
            charset = Charset.forName(encoding);
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的文件编码: " + encoding);
        }
        // Strip the UTF-8 BOM so it does not corrupt the first statement.
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    private FileResource requireWorkerResource(Long fileResourceId) {
        FileResource resource = fileResourceId == null ? null : fileResourceDao.queryById(fileResourceId);
        if (resource == null || !"FILE".equalsIgnoreCase(resource.getResourceType())) {
            throw new IllegalStateException("导入文件资源不存在或不可用");
        }
        return resource;
    }

    private byte[] readFileResource(FileResource resource) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            storageProvider.download(resource.getObjectKey(), output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("读取文件资源失败: " + e.getMessage(), e);
        }
    }

    private void updateProgress(Long taskId, int total, int executed, int failed) {
        offlineImportTaskDao.updateProgressIfRunning(taskId, total, executed, failed);
    }

    private void markTerminated(Long taskId, String status, String errorMessage, String executionLog) {
        OfflineImportTask patch = new OfflineImportTask();
        patch.setId(taskId);
        patch.setStatus(status);
        patch.setErrorMessage(StringUtils.abbreviate(errorMessage, 2000));
        patch.setExecutionLog(executionLog);
        patch.setEndTime(new java.util.Date());
        patch.initUpdate();
        offlineImportTaskDao.updateById(patch);
    }

    private Map<Long, String> dataSourceNames(List<OfflineImportTask> tasks) {
        List<Long> ids = tasks.stream().map(OfflineImportTask::getTargetDatasourceId).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        for (DataSource dataSource : dataSourceDao.queryByIds(ids)) {
            names.put(dataSource.getId(), dataSource.getName());
        }
        return names;
    }

    private OfflineImportTaskVO toVO(OfflineImportTask entity, String dataSourceName) {
        OfflineImportTaskVO vo = new OfflineImportTaskVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setImportType(entity.getImportType());
        vo.setFileResourceId(entity.getFileResourceId());
        vo.setFileName(entity.getFileName());
        vo.setTargetDatasourceId(entity.getTargetDatasourceId());
        vo.setTargetDatasourceName(dataSourceName);
        vo.setTargetSchema(entity.getTargetSchema());
        vo.setOptions(StringUtils.isBlank(entity.getOptionsJson())
                ? null
                : JSONUtils.parseObject(entity.getOptionsJson(), OfflineImportOptions.class));
        vo.setStatus(entity.getStatus());
        vo.setTotalStatements(entity.getTotalStatements());
        vo.setExecutedStatements(entity.getExecutedStatements());
        vo.setFailedStatements(entity.getFailedStatements());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setExecutionLog(entity.getExecutionLog());
        vo.setStartTime(entity.getStartTime());
        vo.setEndTime(entity.getEndTime());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }
}
