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
import org.apache.seatunnel.web.api.offlineimport.OfflineImportLogRedactor;
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

import java.io.BufferedReader;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private final ScheduledExecutorService maintenanceExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "offline-import-task-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /** Tasks this instance currently owns (queued or running). */
    private final java.util.Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();

    private Executor executor = defaultExecutor;

    /** Lets unit tests run task submissions on the calling thread. */
    void setExecutorForTest(Executor testExecutor) {
        this.executor = testExecutor;
    }

    /**
     * Fails tasks whose heartbeat is already stale. A live second instance
     * keeps its own tasks fresh, so this only reclaims work that nobody owns.
     */
    @PostConstruct
    public void initialize() {
        failStaleActive();
        maintenanceExecutor.scheduleWithFixedDelay(this::maintainActiveTasks, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        maintenanceExecutor.shutdownNow();
        defaultExecutor.shutdownNow();
    }

    private void maintainActiveTasks() {
        try {
            java.util.Date now = new java.util.Date();
            for (Long id : activeTaskIds) {
                offlineImportTaskDao.touchActive(id, now);
            }
            failStaleActive();
        } catch (Exception e) {
            log.warn("Offline import task heartbeat failed", e);
        }
    }

    private void failStaleActive() {
        long cutoffMillis = System.currentTimeMillis()
                - offlineImportProperties.getStaleActiveMinutes() * 60_000L;
        int failed = offlineImportTaskDao.failStaleActive(
                new java.util.Date(cutoffMillis),
                "任务心跳超时或服务重启导致中断，请重新执行");
        if (failed > 0) {
            log.warn("Failed {} stale offline import tasks", failed);
        }
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
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(importType.expectedExtension())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "文件 " + fileName + " 与导入类型不匹配，应为 " + importType.expectedExtension() + " 文件");
        }
        long size = resource.getSize() == null ? 0L : resource.getSize();
        if (size <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "导入文件不能为空");
        }
        long maxBytes = importType.isSql()
                ? offlineImportProperties.getSqlMaxFileBytes()
                : offlineImportProperties.getDmpMaxFileBytes();
        if (size > maxBytes) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "文件超过大小上限 " + maxBytes + " 字节");
        }
        OfflineImportOptions options = defaultOptions(dto.getOptions());
        validateOptions(importType, options, dto.getTargetSchema());

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
        entity.setOptionsJson(JSONUtils.toJsonString(options));
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

    private void validateOptions(
            OfflineImportType importType, OfflineImportOptions options, String targetSchema) {
        if (importType.isSql()) {
            try {
                Charset.forName(options.getEncoding());
            } catch (Exception e) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                        "不支持的文件编码: " + options.getEncoding());
            }
            return;
        }
        String sourceSchema = StringUtils.trimToNull(options.getSourceSchema());
        String target = StringUtils.trimToNull(targetSchema);
        if (importType == OfflineImportType.KINGBASE_DMP
                && sourceSchema != null && target != null && !sourceSchema.equals(target)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "人大金仓 DMP 不支持 Schema 重映射，源 Schema 与目标 Schema 必须一致");
        }
    }

    @Override
    public PaginationResult<OfflineImportTaskVO> page(OfflineImportTaskQueryDTO query) {
        IPage<OfflineImportTask> page = offlineImportTaskDao.queryPage(
                query, currentUserProvider.getCurrentUserId());
        Map<Long, String> dataSourceNames = dataSourceNames(page.getRecords());
        return PaginationResult.buildSuc(
                page.getRecords().stream()
                        .map(entity -> {
                            OfflineImportTaskVO vo = toVO(
                                    entity, dataSourceNames.get(entity.getTargetDatasourceId()));
                            // The list never renders the log; keep the page payload small.
                            vo.setExecutionLog(null);
                            return vo;
                        })
                        .toList(),
                page);
    }

    @Override
    public OfflineImportTaskVO get(Long id) {
        OfflineImportTask task = requireOwnedTask(id);
        DataSource dataSource = dataSourceDao.queryById(task.getTargetDatasourceId());
        return toVO(task, dataSource == null ? null : dataSource.getName());
    }

    @Override
    public void delete(Long id) {
        requireOwnedTask(id);
        if (!offlineImportTaskDao.deleteIfNotRunning(id)) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务正在执行中，不能删除");
        }
    }

    @Override
    public void execute(Long id) {
        requireOwnedTask(id);
        if (!offlineImportTaskDao.markQueuedIfExecutable(id, new java.util.Date())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务正在排队或执行中");
        }
        activeTaskIds.add(id);
        try {
            executor.execute(() -> {
                try {
                    runTask(id);
                } catch (Throwable t) {
                    log.error("Offline import task {} crashed", id, t);
                    markTerminated(id, STATUS_FAILED,
                            "任务执行异常: "
                                    + StringUtils.defaultIfBlank(t.getMessage(), t.getClass().getSimpleName()),
                            null);
                }
            });
        } catch (RejectedExecutionException e) {
            activeTaskIds.remove(id);
            offlineImportTaskDao.markTerminatedIfActive(
                    id, STATUS_FAILED, "执行队列已关闭，请稍后重试", null, new java.util.Date());
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_EXECUTE_ERROR, "执行队列已关闭");
        }
    }

    private OfflineImportTask requireTask(Long id) {
        OfflineImportTask task = id == null ? null : offlineImportTaskDao.queryById(id);
        if (task == null) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_NOT_EXIST, String.valueOf(id));
        }
        return task;
    }

    private OfflineImportTask requireOwnedTask(Long id) {
        OfflineImportTask task = requireTask(id);
        Integer ownerId = currentUserProvider.getCurrentUserId();
        if (task.getCreateUserId() == null || !task.getCreateUserId().equals(ownerId)) {
            // Do not reveal that another user's task exists.
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_NOT_EXIST, String.valueOf(id));
        }
        return task;
    }

    private void runTask(Long id) {
        try {
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
                        ? runSqlImport(task, dataSource, options, executionLog)
                        : runDmpImport(task, importType, dataSource, options, executionLog);
                boolean success = failure == null;
                executionLog.line(success ? "任务执行成功" : "任务执行失败: " + failure);
                markTerminated(id, success ? STATUS_SUCCESS : STATUS_FAILED, failure, executionLog.snapshot());
            } catch (Exception e) {
                log.error("Offline import task {} failed", id, e);
                String failure = "任务执行异常: "
                        + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
                executionLog.line(failure);
                markTerminated(id, STATUS_FAILED, failure, executionLog.snapshot());
            }
        } finally {
            activeTaskIds.remove(id);
        }
    }

    private String runSqlImport(
            OfflineImportTask task,
            DataSource dataSource,
            OfflineImportOptions options,
            ExecutionLogBuffer executionLog) throws Exception {
        FileResource resource = requireWorkerResource(task.getFileResourceId());
        long maxBytes = offlineImportProperties.getSqlMaxFileBytes();
        long size = resource.getSize() == null ? 0L : resource.getSize();
        if (size > maxBytes) {
            return "SQL 文件超过大小上限 " + maxBytes + " 字节";
        }
        long heapLimit = Runtime.getRuntime().maxMemory() / 3;
        if (size > heapLimit) {
            return "SQL 文件过大（" + size + " 字节），当前服务堆内存不足以安全解析；"
                    + "请拆分脚本或改用 DMP 导入";
        }

        Path workDir = Paths.get(offlineImportProperties.getWorkDir())
                .toAbsolutePath()
                .resolve("task-" + task.getId());
        Files.createDirectories(workDir);
        Path sqlFile = workDir.resolve("import.sql");
        try {
            try (java.io.OutputStream output = Files.newOutputStream(sqlFile)) {
                storageProvider.download(resource.getObjectKey(), output);
            }
            long actualSize = Files.size(sqlFile);
            if (actualSize > maxBytes) {
                return "SQL 文件超过大小上限 " + maxBytes + " 字节";
            }
            if (actualSize > heapLimit) {
                return "SQL 文件过大（" + actualSize + " 字节），当前服务堆内存不足以安全解析；"
                        + "请拆分脚本或改用 DMP 导入";
            }
            String text = readText(sqlFile, options.getEncoding());
            executionLog.line("已读取 SQL 文件 " + task.getFileName()
                    + "（" + text.length() + " 字符，编码 " + options.getEncoding() + "）");

            List<String> statements = SqlScriptSplitter.split(text);
            executionLog.line("解析得到 " + statements.size() + " 条语句");
            if (statements.isEmpty()) {
                return "SQL 文件中未解析到可执行语句";
            }
            updateProgress(task.getId(), statements.size(), 0, 0);

            BaseConnectionParam connectionParam = (BaseConnectionParam) DataSourceUtils.buildConnectionParams(
                    dataSource.getDbType(), dataSource.getConnectionParams());
            try (Connection connection = DataSourceUtils.getDatasourceProcessor(dataSource.getDbType())
                    .getConnectionManager()
                    .getConnection(connectionParam)) {
                // Import scripts manage their own BEGIN/COMMIT; the driver's
                // autocommit mode must not silently wrap every statement.
                connection.setAutoCommit(true);
                int timeout = (int) Math.min(
                        Integer.MAX_VALUE, offlineImportProperties.getSqlStatementTimeoutSeconds());
                SqlScriptImportRunner runner = new SqlScriptImportRunner(connection, executionLog, timeout);
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
        } finally {
            deleteRecursively(workDir);
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
            // Never reuse the user-supplied name: dimp rejects several
            // characters and treats "%U" as a multi-file template.
            Path dmpFile = workDir.resolve("import.dmp");
            Path toolLogFile = workDir.resolve("import.log");
            // Stream straight to disk: DMP backups may be far larger than the heap.
            FileResource dmpResource = requireWorkerResource(task.getFileResourceId());
            long size = dmpResource.getSize() == null ? 0L : dmpResource.getSize();
            long maxBytes = offlineImportProperties.getDmpMaxFileBytes();
            if (size > maxBytes) {
                return "DMP 文件超过大小上限 " + maxBytes + " 字节";
            }
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(dmpFile)) {
                storageProvider.download(dmpResource.getObjectKey(), output);
            }
            if (Files.size(dmpFile) > maxBytes) {
                return "DMP 文件超过大小上限 " + maxBytes + " 字节";
            }
            executionLog.line("已下载 DMP 文件 " + task.getFileName() + "，调用 " + toolName + " 导入");

            String sourceSchema = StringUtils.trimToNull(options.getSourceSchema());
            String targetSchema = StringUtils.trimToNull(task.getTargetSchema());
            if (importType == OfflineImportType.KINGBASE_DMP
                    && sourceSchema != null && targetSchema != null && !sourceSchema.equals(targetSchema)) {
                return "人大金仓 DMP 不支持 Schema 重映射，源 Schema 与目标 Schema 必须一致";
            }
            BaseConnectionParam param = (BaseConnectionParam) DataSourceUtils.buildConnectionParams(
                    dataSource.getDbType(), dataSource.getConnectionParams());
            DmpCommandBuilder.DmpCommand command;
            if (importType == OfflineImportType.DAMENG_DMP) {
                command = DmpCommandBuilder.dameng(
                        toolPath, param.getHost(), param.getPort(), param.getUser(), param.getPassword(),
                        dmpFile, toolLogFile,
                        sourceSchema, targetSchema,
                        Boolean.TRUE.equals(options.getIgnoreErrors()));
            } else {
                command = DmpCommandBuilder.kingbase(
                        toolPath, param.getHost(), param.getPort(), param.getDatabase(),
                        param.getUser(), param.getPassword(), dmpFile,
                        sourceSchema, targetSchema);
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
            long length = Files.size(toolLogFile);
            int readLength = (int) Math.min(length, 8 * 1024);
            if (readLength == 0) {
                return;
            }
            byte[] bytes = new byte[readLength];
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(toolLogFile.toFile(), "r")) {
                file.seek(length - readLength);
                file.readFully(bytes);
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (StringUtils.isNotBlank(content)) {
                executionLog.line("工具日志尾部:");
                // Append line by line: raw() caps one line at 500 chars, and
                // passing the whole block would keep only its first 500 chars.
                for (String line : content.split("\n", -1)) {
                    executionLog.raw(line);
                }
            }
        } catch (IOException e) {
            log.warn("Read tool log file failed: {}", toolLogFile, e);
        }
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

    private FileResource requireWorkerResource(Long fileResourceId) {
        FileResource resource = fileResourceId == null ? null : fileResourceDao.queryById(fileResourceId);
        if (resource == null
                || !"FILE".equalsIgnoreCase(resource.getResourceType())
                || !"READY".equalsIgnoreCase(resource.getStatus())) {
            throw new IllegalStateException("导入文件资源不存在或不可用");
        }
        return resource;
    }

    private String readText(Path file, String encoding) throws IOException {
        Charset charset;
        try {
            charset = Charset.forName(encoding);
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的文件编码: " + encoding);
        }
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            text.deleteCharAt(0);
        }
        return text.toString();
    }

    private void updateProgress(Long taskId, int total, int executed, int failed) {
        offlineImportTaskDao.updateProgressIfRunning(taskId, total, executed, failed);
    }

    private void markTerminated(Long taskId, String status, String errorMessage, String executionLog) {
        java.util.Date endTime = new java.util.Date();
        boolean updated = offlineImportTaskDao.markTerminatedIfActive(
                taskId,
                status,
                OfflineImportLogRedactor.mask(StringUtils.abbreviate(errorMessage, 2000)),
                executionLog,
                endTime);
        if (!updated) {
            log.info("Offline import task {} terminal update skipped; state changed meanwhile", taskId);
        }
    }

    private Map<Long, String> dataSourceNames(List<OfflineImportTask> tasks) {
        List<Long> ids = tasks.stream().map(OfflineImportTask::getTargetDatasourceId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        List<DataSource> dataSources = dataSourceDao.queryByIds(ids);
        if (dataSources != null) {
            for (DataSource dataSource : dataSources) {
                names.put(dataSource.getId(), dataSource.getName());
            }
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
