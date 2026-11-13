package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.api.offlineimport.DmpArchivePreflight;
import org.apache.seatunnel.web.api.offlineimport.DatapackImportRunner;
import org.apache.seatunnel.web.api.offlineimport.DatapackPackageInspector;
import org.apache.seatunnel.web.api.offlineimport.DmpCommandBuilder;
import org.apache.seatunnel.web.api.offlineimport.DmpProcessRunner;
import org.apache.seatunnel.web.api.offlineimport.ExecutionLogBuffer;
import org.apache.seatunnel.web.api.offlineimport.OfflineImportProperties;
import org.apache.seatunnel.web.api.offlineimport.OfflineImportLogRedactor;
import org.apache.seatunnel.web.api.offlineimport.SqlImportPlan;
import org.apache.seatunnel.web.api.offlineimport.SqlSchemaMapper;
import org.apache.seatunnel.web.api.offlineimport.SqlScriptImportRunner;
import org.apache.seatunnel.web.api.offlineimport.SqlScriptSplitter;
import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.FileResourceService;
import org.apache.seatunnel.web.api.service.DataSourceUsageValidator;
import org.apache.seatunnel.web.api.service.DataSourceCatalogService;
import org.apache.seatunnel.web.api.service.OfflineImportTaskService;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.dao.entity.OfflineImportTaskRun;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.FileResourceDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskRunDao;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportOptions;
import org.apache.seatunnel.web.spi.bean.dto.OfflineDatapackTaskConfig;
import org.apache.seatunnel.web.spi.bean.dto.OfflineDatapackCsvSelectDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.FileResourceVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskRunVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineDatapackFormVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineDatapackInspectVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineDatapackCsvInspectVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineDatapackCsvSelectVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.enums.OfflineImportType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** One package at a time: encrypted bytes, plaintext and the retained JSON tree coexist in memory. */
    private static final Semaphore DATAPACK_MEMORY_BUDGET = new Semaphore(1, true);
    @Resource
    private OfflineImportTaskDao offlineImportTaskDao;

    @Resource
    private OfflineImportTaskRunDao offlineImportTaskRunDao;

    @Resource
    private OfflineImportTaskRunCompletionService offlineImportTaskRunCompletionService;

    @Resource
    private DataSourceDao dataSourceDao;

    @Resource
    private DataSourceUsageValidator dataSourceUsageValidator;

    @Resource
    private DataSourceCatalogService dataSourceCatalogService;

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

    @Resource
    private DatapackPackageInspector datapackPackageInspector;

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
                offlineImportTaskRunDao.touchActiveRun(id, now);
            }
            failStaleActive();
        } catch (Exception e) {
            log.warn("Offline import task heartbeat failed", e);
        }
    }

    private void failStaleActive() {
        long cutoffMillis = System.currentTimeMillis()
                - offlineImportProperties.getStaleActiveMinutes() * 60_000L;
        Date cutoff = new Date(cutoffMillis);
        Date endTime = new Date();
        int failed = offlineImportTaskDao.failStaleActive(
                cutoff,
                "任务心跳超时或服务重启导致中断，请重新执行");
        int failedRuns = offlineImportTaskRunDao.failStaleRuns(
                cutoff, "任务心跳超时或服务重启导致中断，请重新执行", endTime);
        if (failed > 0) {
            log.warn("Failed {} stale offline import tasks and {} run records", failed, failedRuns);
        }
    }

    @Override
    public OfflineImportTaskVO create(OfflineImportTaskCreateDTO dto) {
        ValidatedImportConfig config = validateConfig(dto);

        OfflineImportTask entity = new OfflineImportTask();
        entity.initInsert();
        entity.setName(dto.getName().trim());
        entity.setDescription(StringUtils.trimToNull(dto.getDescription()));
        entity.setImportType(config.importType().getCode());
        entity.setFileResourceId(dto.getFileResourceId());
        entity.setFileName(config.fileName());
        entity.setTargetDatasourceId(dto.getTargetDatasourceId());
        entity.setTargetDatabase(StringUtils.trimToNull(dto.getTargetDatabase()));
        entity.setTargetSchema(StringUtils.trimToNull(dto.getTargetSchema()));
        entity.setOptionsJson(JSONUtils.toJsonString(config.options()));
        entity.setDatapackConfigJson(config.datapackConfig() == null
                ? null : JSONUtils.toJsonString(config.datapackConfig()));
        entity.setScheduleConfigJson(dto.getScheduleConfig() == null
                ? null
                : JSONUtils.toJsonString(dto.getScheduleConfig()));
        entity.setReleaseState("OFFLINE");
        entity.setStatus(STATUS_PENDING);
        entity.setTotalStatements(0);
        entity.setExecutedStatements(0);
        entity.setFailedStatements(0);
        entity.setCreateUserId(currentUserProvider.getCurrentUserId());
        offlineImportTaskDao.insert(entity);
        log.info("Created offline import task taskId={}, ownerId={}, type={}, targetDatabase={}",
                entity.getId(), entity.getCreateUserId(), entity.getImportType(), entity.getTargetDatabase());
        return toVO(entity, config.target().getName());
    }

    @Override
    public OfflineImportTaskVO update(Long id, OfflineImportTaskCreateDTO dto) {
        OfflineImportTask task = requireTask(id);
        if (!"OFFLINE".equals(task.getReleaseState())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "请先下线任务，再编辑配置");
        }
        if (isActive(task.getStatus())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务正在排队或执行中，不能编辑配置");
        }

        ValidatedImportConfig config = validateConfig(dto);
        OfflineImportTask update = new OfflineImportTask();
        update.setName(dto.getName().trim());
        update.setDescription(StringUtils.trimToNull(dto.getDescription()));
        update.setImportType(config.importType().getCode());
        update.setFileResourceId(dto.getFileResourceId());
        update.setFileName(config.fileName());
        update.setTargetDatasourceId(dto.getTargetDatasourceId());
        update.setTargetDatabase(StringUtils.trimToNull(dto.getTargetDatabase()));
        update.setTargetSchema(StringUtils.trimToNull(dto.getTargetSchema()));
        update.setOptionsJson(JSONUtils.toJsonString(config.options()));
        update.setDatapackConfigJson(config.datapackConfig() == null
                ? null : JSONUtils.toJsonString(config.datapackConfig()));
        update.setScheduleConfigJson(dto.getScheduleConfig() == null
                ? null
                : JSONUtils.toJsonString(dto.getScheduleConfig()));
        if (!offlineImportTaskDao.updateConfigurationIfOffline(
                id, task.getCreateUserId(), update, new Date())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR,
                    "任务状态已变化，请刷新后再编辑");
        }
        log.info(
                "Updated offline import task configuration taskId={}, ownerId={}, targetDatabase={}, hasScheduleConfig={}",
                id, task.getCreateUserId(), update.getTargetDatabase(), dto.getScheduleConfig() != null);
        return toVO(offlineImportTaskDao.queryById(id), config.target().getName());
    }

    private ValidatedImportConfig validateConfig(OfflineImportTaskCreateDTO dto) {
        OfflineImportType importType = OfflineImportType.of(dto.getImportType());
        if (importType == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "importType");
        }
        FileResourceVO resourceView = fileResourceService.get(dto.getFileResourceId());
        if (!"FILE".equalsIgnoreCase(resourceView.getResourceType())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "只能选择文件资源");
        }
        String fileName = StringUtils.defaultIfBlank(resourceView.getName(), "");
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(importType.expectedExtension())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "文件 " + fileName + " 与导入类型不匹配，应为 " + importType.expectedExtension() + " 文件");
        }
        long size = resourceView.getSize() == null ? 0L : resourceView.getSize();
        if (size <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "导入文件不能为空");
        }
        long maxBytes = importType.isDatapack()
                ? offlineImportProperties.getDatapackMaxFileBytes()
                : importType.isSql() ? offlineImportProperties.getSqlMaxFileBytes()
                : offlineImportProperties.getDmpMaxFileBytes();
        if (size > maxBytes) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "文件超过大小上限 " + maxBytes + " 字节");
        }
        OfflineImportOptions options = defaultOptions(dto.getOptions());
        validateOptions(importType, options, dto.getTargetSchema());

        DataSource target = dataSourceDao.queryById(dto.getTargetDatasourceId());
        if (target == null) {
            throw new ServiceException(Status.DATASOURCE_NOT_EXIST);
        }
        if (!importType.isDatapack() && target.getDbType() != importType.getTargetDbType()) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "目标数据源类型 " + target.getDbType() + " 与导入类型 " + importType.getCode() + " 不匹配");
        }
        String targetDatabase = StringUtils.trimToNull(dto.getTargetDatabase());
        boolean kingbaseDmp = importType == OfflineImportType.KINGBASE_DMP;
        if ((importType.isSql() || importType.isDatapack() || kingbaseDmp) && targetDatabase == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "请选择目标数据库");
        }
        if (importType == OfflineImportType.DAMENG_DMP && targetDatabase != null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "达梦 DMP 的目标数据库实例由所选数据源连接确定");
        }
        if (importType.isSql() || importType.isDatapack() || kingbaseDmp) {
            List<OptionVO> databases = dataSourceCatalogService.listDatabase(target.getId());
            boolean databaseExists = databases.stream()
                    .anyMatch(option -> targetDatabase.equals(option.getValue()));
            if (!databaseExists) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                        "目标数据库不属于所选数据源: " + targetDatabase);
            }
        }
        String targetSchema = StringUtils.trimToNull(dto.getTargetSchema());
        if ((importType == OfflineImportType.KINGBASE_DMP || importType == OfflineImportType.DAMENG_DMP)
                && targetSchema != null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "DMP 导入按备份中的 Schema 名称还原，不支持目标 Schema 映射");
        }
        if (importType.isDatapack() && targetSchema != null) {
            boolean schemaExists = dataSourceCatalogService.listSchema(target.getId(), targetDatabase).stream()
                    .anyMatch(option -> targetSchema.equals(option.getValue()));
            if (!schemaExists) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                        "目标 Schema 不属于所选数据库");
            }
        }
        OfflineDatapackTaskConfig datapackConfig = null;
        if (importType.isDatapack()) {
            datapackConfig = validateDatapackConfig(dto, target, targetDatabase);
        }
        dataSourceUsageValidator.validate(target.getId(),
                org.apache.seatunnel.web.common.enums.DataSourceUsage.SINK);
        return new ValidatedImportConfig(importType, fileName, options, target, datapackConfig);
    }

    private OfflineDatapackTaskConfig validateDatapackConfig(
            OfflineImportTaskCreateDTO dto, DataSource target, String targetDatabase) {
        OfflineDatapackTaskConfig config = dto.getDatapackConfig();
        char[] password = dto.getDatapackPassword();
        byte[] encrypted = null;
        boolean memoryBudgetAcquired = false;
        try {
            if (config == null || StringUtils.isBlank(config.getPackageFingerprint())
                    || !config.getPackageFingerprint().matches("(?i)[0-9a-f]{64}")
                    || StringUtils.isBlank(config.getFormCode())
                    || StringUtils.isBlank(config.getTargetTable())
                    || config.getFormCode().length() > 128 || config.getTargetTable().length() > 256
                    || config.getMappings() == null || config.getMappings().isEmpty()
                    || config.getMappings().size() > 512
                    || config.getMappings().stream().anyMatch(mapping -> mapping == null
                            || StringUtils.isBlank(mapping.getSourceField())
                            || StringUtils.isBlank(mapping.getTargetField())
                            || mapping.getSourceField().length() > 128
                            || mapping.getTargetField().length() > 128)) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "数据包表单和字段映射不能为空");
            }
            int configBytes = JSONUtils.toJsonString(config).getBytes(StandardCharsets.UTF_8).length;
            if (configBytes > offlineImportProperties.getDatapackTaskConfigMaxBytes()) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                        "数据包字段映射配置超过 UTF-8 字节上限");
            }
            if (password == null || password.length == 0) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "请重新输入数据包密码以保存配置");
            }
            if (!tryAcquireDatapackMemoryBudget("configuration validation")) {
                throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR,
                        "当前已有数据包正在校验或导入，请稍后重试");
            }
            memoryBudgetAcquired = true;
            FileResource resource = requireWorkerResource(dto.getFileResourceId(), currentUserProvider.getCurrentUserId());
            encrypted = readDatapackResource(resource);
            DatapackPackageInspector.Inspection inspection = datapackPackageInspector.inspect(encrypted, password);
            if (!inspection.fingerprint().equalsIgnoreCase(config.getPackageFingerprint())) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                        "数据包内容已变化，请重新检查并配置");
            }

            BaseConnectionParam connectionParam = targetConnectionParam(target, targetDatabase);
            try (Connection connection = DataSourceUtils.getDatasourceProcessor(target.getDbType())
                    .getConnectionManager().getConnection(connectionParam)) {
                DatapackImportRunner.validateTarget(
                        connection, StringUtils.trimToNull(dto.getTargetSchema()), config, inspection);
            } catch (ServiceException exception) {
                throw exception;
            } catch (IllegalArgumentException exception) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, exception.getMessage());
            } catch (Exception exception) {
                throw new ServiceException(Status.DATASOURCE_METADATA_ERROR,
                        "无法校验目标表和字段映射，请检查目标数据源连接");
            }
            return config;
        } finally {
            if (memoryBudgetAcquired) {
                DATAPACK_MEMORY_BUDGET.release();
            }
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
        }
    }

    private BaseConnectionParam targetConnectionParam(DataSource target, String targetDatabase) {
        Map<String, Object> connectionParams = JSONUtils.parseObject(
                target.getConnectionParams(), new TypeReference<Map<String, Object>>() {});
        if (connectionParams == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "目标数据源连接参数无效");
        }
        connectionParams.put("database", targetDatabase);
        return (BaseConnectionParam) DataSourceUtils.buildConnectionParams(
                target.getDbType(), JSONUtils.toJsonString(connectionParams));
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
        OfflineImportTask task = requireTask(id);
        DataSource dataSource = dataSourceDao.queryById(task.getTargetDatasourceId());
        return toVO(task, dataSource == null ? null : dataSource.getName());
    }

    @Override
    public OfflineDatapackInspectVO inspectDatapack(Long fileResourceId, char[] password) {
        byte[] encrypted = null;
        boolean memoryBudgetAcquired = false;
        try {
            FileResourceVO resourceView = fileResourceService.get(fileResourceId);
            if (!"FILE".equalsIgnoreCase(resourceView.getResourceType())
                    || !StringUtils.defaultString(resourceView.getName()).toLowerCase(Locale.ROOT).endsWith(".datapack")) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                        "请选择 .datapack 文件资源");
            }
            FileResource resource = requireWorkerResource(fileResourceId, currentUserProvider.getCurrentUserId());
            long size = resource.getSize() == null ? 0L : resource.getSize();
            if (size <= 0 || size > offlineImportProperties.getDatapackMaxFileBytes()) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                        "数据包为空或超过大小上限");
            }
            if (!tryAcquireDatapackMemoryBudget("inspection")) {
                throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR,
                        "当前已有数据包正在校验或导入，请稍后重试");
            }
            memoryBudgetAcquired = true;
            encrypted = readDatapackResource(resource);
            DatapackPackageInspector.Inspection inspection = datapackPackageInspector.inspect(encrypted, password);
            OfflineDatapackInspectVO result = new OfflineDatapackInspectVO();
            result.setPackageFingerprint(inspection.fingerprint());
            result.setPackageId(inspection.packageId());
            result.setPackageType(inspection.packageType());
            result.setRecordCount(inspection.recordCount());
            result.setForms(inspection.forms().stream()
                    .map(form -> new OfflineDatapackFormVO(form.formCode(), form.recordCount(), form.fields()))
                    .toList());
            log.info("Inspected offline datapack resourceId={}, ownerId={}, type={}, records={}, forms={}",
                    fileResourceId, currentUserProvider.getCurrentUserId(),
                    inspection.packageType(), inspection.recordCount(), inspection.forms().size());
            return result;
        } finally {
            if (memoryBudgetAcquired) {
                DATAPACK_MEMORY_BUDGET.release();
            }
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
        }
    }

    @Override
    public OfflineDatapackCsvInspectVO inspectDatapackCsv(Long fileResourceId, char[] password) {
        byte[] encrypted = null;
        boolean memoryBudgetAcquired = false;
        try {
            FileResource resource = requireOwnedDatapack(fileResourceId);
            long size = resource.getSize() == null ? 0L : resource.getSize();
            if (size <= 0 || size > offlineImportProperties.getDatapackCsvMaxFileBytes()) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "数据包为空或超过大小上限");
            }
            if (!tryAcquireDatapackMemoryBudget("CSV inspection")) {
                throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR,
                        "当前已有数据包正在校验或导入，请稍后重试");
            }
            memoryBudgetAcquired = true;
            encrypted = readDatapackResource(resource, offlineImportProperties.getDatapackCsvMaxFileBytes());
            DatapackPackageInspector.CsvArchiveInspection inspection =
                    datapackPackageInspector.inspectCsvArchive(encrypted, password);
            log.info("Inspected datapack CSV members resourceId={}, ownerId={}, csvCount={}",
                    fileResourceId, currentUserProvider.getCurrentUserId(), inspection.fileNames().size());
            return new OfflineDatapackCsvInspectVO(inspection.fingerprint(), inspection.fileNames());
        } finally {
            if (memoryBudgetAcquired) {
                DATAPACK_MEMORY_BUDGET.release();
            }
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
        }
    }

    @Override
    public OfflineDatapackCsvSelectVO selectDatapackCsv(OfflineDatapackCsvSelectDTO request) {
        byte[] encrypted = null;
        byte[] csv = null;
        boolean memoryBudgetAcquired = false;
        try {
            if (request == null) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "数据包 CSV 选择参数无效");
            }
            FileResource resource = requireOwnedDatapack(request.getFileResourceId());
            long size = resource.getSize() == null ? 0L : resource.getSize();
            if (size <= 0 || size > offlineImportProperties.getDatapackCsvMaxFileBytes()) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "数据包为空或超过大小上限");
            }
            if (!tryAcquireDatapackMemoryBudget("CSV extraction")) {
                throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR,
                        "当前已有数据包正在校验或导入，请稍后重试");
            }
            memoryBudgetAcquired = true;
            encrypted = readDatapackResource(resource, offlineImportProperties.getDatapackCsvMaxFileBytes());
            DatapackPackageInspector.CsvSelection selection = datapackPackageInspector.extractCsv(
                    encrypted,
                    request.getPassword(),
                    request.getPackageFingerprint(),
                    request.getFileName());
            csv = selection.content();

            // A unique first-level directory keeps extracted resources from sharing a parent path.
            String uniquePath = "/offline-datapack-" + UUID.randomUUID();
            List<FileResourceVO> resources = fileResourceService.upload(
                    uniquePath,
                    new MultipartFile[] {new DatapackCsvMultipartFile(selection.fileName(), csv)},
                    new String[] {selection.fileName()});
            if (resources == null || resources.isEmpty()) {
                throw new IllegalStateException("文件资源服务未登记所选 CSV");
            }

            OfflineDatapackCsvSelectVO result = new OfflineDatapackCsvSelectVO();
            result.setFileResource(resources.get(0));
            result.setSchema(selection.schema());
            result.setNullValue(selection.nullValue());
            log.info("Materialized selected datapack CSV packageResourceId={}, ownerId={}, fileName={},"
                            + " fileResourceId={}, size={}",
                    request.getFileResourceId(), currentUserProvider.getCurrentUserId(), selection.fileName(),
                    resources.get(0).getId(), csv.length);
            return result;
        } finally {
            if (memoryBudgetAcquired) {
                DATAPACK_MEMORY_BUDGET.release();
            }
            if (request != null && request.getPassword() != null) {
                Arrays.fill(request.getPassword(), '\0');
            }
            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
            if (csv != null) {
                Arrays.fill(csv, (byte) 0);
            }
        }
    }

    private FileResource requireOwnedDatapack(Long fileResourceId) {
        FileResourceVO resourceView = fileResourceService.get(fileResourceId);
        if (!"FILE".equalsIgnoreCase(resourceView.getResourceType())
                || !StringUtils.defaultString(resourceView.getName()).toLowerCase(Locale.ROOT).endsWith(".datapack")) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "请选择 .datapack 文件资源");
        }
        return requireWorkerResource(fileResourceId, currentUserProvider.getCurrentUserId());
    }

    @Override
    public List<OfflineImportTaskRunVO> history(Long id) {
        OfflineImportTask task = requireTask(id);
        List<OfflineImportTaskRun> runs = offlineImportTaskRunDao.queryByTaskId(
                id, currentUserProvider.getCurrentUserId());
        if (runs.isEmpty() && task.getStartTime() != null) {
            // Tasks created before the history table migration still expose their last saved run.
            OfflineImportTaskRunVO legacyRun = new OfflineImportTaskRunVO();
            legacyRun.setId(task.getId());
            legacyRun.setTaskName(task.getName());
            legacyRun.setStatus(task.getStatus());
            legacyRun.setTotalStatements(task.getTotalStatements());
            legacyRun.setExecutedStatements(task.getExecutedStatements());
            legacyRun.setFailedStatements(task.getFailedStatements());
            legacyRun.setErrorMessage(task.getErrorMessage());
            legacyRun.setExecutionLog(task.getExecutionLog());
            legacyRun.setStartTime(task.getStartTime());
            legacyRun.setEndTime(task.getEndTime());
            return List.of(legacyRun);
        }
        return runs.stream().map(this::toRunVO).toList();
    }

    @Override
    public void delete(Long id) {
        OfflineImportTask task = requireTask(id);
        if (isActive(task.getStatus())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务正在排队或执行中，不能删除");
        }
        if (!"OFFLINE".equals(task.getReleaseState())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "请先下线任务，再删除");
        }
        if (!offlineImportTaskDao.deleteIfNotRunning(id, task.getCreateUserId())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务状态已变化，不能删除");
        }
    }

    @Override
    public void execute(Long id) {
        execute(id, null);
    }

    @Override
    public void execute(Long id, char[] password) {
        char[] workerPassword = null;
        Integer ownerId = null;
        boolean queued = false;
        try {
            OfflineImportTask task = requireTask(id);
            OfflineImportType importType = OfflineImportType.of(task.getImportType());
            if (importType != null && importType.isDatapack()) {
                if (password == null || password.length == 0) {
                    throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "执行数据包导入时需要输入数据包密码");
                }
                workerPassword = password.clone();
            }
            if (!"ONLINE".equals(task.getReleaseState())) {
                throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "请先上线任务，再启动导入");
            }
            ownerId = task.getCreateUserId();
            if (!offlineImportTaskDao.markQueuedIfExecutable(id, ownerId, new java.util.Date())) {
                throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务状态已变化，请刷新后再启动");
            }
            queued = true;
            activeTaskIds.add(id);
            final Integer taskOwnerId = ownerId;
            final char[] transientPassword = workerPassword;
            executor.execute(() -> {
                try {
                    runTask(id, taskOwnerId, transientPassword);
                } catch (Throwable t) {
                    log.error("Offline import task {} crashed ({})", id, t.getClass().getSimpleName());
                    markTerminated(id, null, STATUS_FAILED, "任务执行异常", null);
                } finally {
                    if (transientPassword != null) {
                        Arrays.fill(transientPassword, '\0');
                    }
                    activeTaskIds.remove(id);
                }
            });
            workerPassword = null;
        } catch (RejectedExecutionException e) {
            activeTaskIds.remove(id);
            if (queued) {
                offlineImportTaskDao.failQueued(
                        id, ownerId, "任务执行器当前不可用，请稍后重试", new java.util.Date());
            }
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务执行器当前不可用，请稍后重试");
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (workerPassword != null) {
                Arrays.fill(workerPassword, '\0');
            }
        }
    }

    @Override
    public void updateReleaseState(Long id, String releaseState) {
        if (!"ONLINE".equals(releaseState) && !"OFFLINE".equals(releaseState)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "releaseState");
        }
        OfflineImportTask task = requireTask(id);
        if (isActive(task.getStatus())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR, "任务正在排队或执行中，不能切换上线状态");
        }
        if (releaseState.equals(task.getReleaseState())) {
            return;
        }
        if (!offlineImportTaskDao.updateReleaseStateIfIdle(
                id, task.getCreateUserId(), task.getReleaseState(), releaseState, new Date())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_STATE_ERROR,
                    "任务状态已变化，请刷新后再切换上线状态");
        }
        log.info("Changed offline import task release state taskId={}, ownerId={}, releaseState={}",
                id, task.getCreateUserId(), releaseState);
    }

    private boolean isActive(String status) {
        return STATUS_RUNNING.equals(status) || STATUS_QUEUED.equals(status);
    }

    private OfflineImportTask requireTask(Long id) {
        return requireTask(id, currentUserProvider.getCurrentUserId());
    }

    private OfflineImportTask requireTask(Long id, Integer ownerId) {
        OfflineImportTask task = id == null ? null : offlineImportTaskDao.queryById(id);
        if (task == null || ownerId == null || !ownerId.equals(task.getCreateUserId())) {
            throw new ServiceException(Status.OFFLINE_IMPORT_TASK_NOT_EXIST, String.valueOf(id));
        }
        return task;
    }

    private void runTask(Long id, Integer ownerId, char[] password) {
        // QUEUED → RUNNING marks the real start; a task deleted while queued stays deleted.
        if (!offlineImportTaskDao.markRunningFromQueued(id, ownerId, new java.util.Date())) {
            log.info("Offline import task {} is no longer queued, skip run", id);
            return;
        }
        ExecutionLogBuffer executionLog = new ExecutionLogBuffer(LOG_MAX_CHARS);
        Long runId = null;
        OfflineImportType importType = null;

        try {
            OfflineImportTask task = requireTask(id, ownerId);
            importType = OfflineImportType.of(task.getImportType());
            OfflineImportOptions options = defaultOptions(
                    JSONUtils.parseObject(task.getOptionsJson(), OfflineImportOptions.class));
            DataSource dataSource = dataSourceDao.queryById(task.getTargetDatasourceId());
            executionLog.line("开始执行离线导入任务: " + task.getName() + "（类型 " + task.getImportType() + "）");

            OfflineImportTaskRun run = new OfflineImportTaskRun();
            run.initInsert();
            run.setTaskId(id);
            run.setTaskName(task.getName());
            run.setStatus(STATUS_RUNNING);
            run.setTotalStatements(0);
            run.setExecutedStatements(0);
            run.setFailedStatements(0);
            run.setExecutionLog(executionLog.snapshot());
            run.setStartTime(task.getStartTime() == null ? new Date() : task.getStartTime());
            run.setCreateUserId(ownerId);
            offlineImportTaskRunDao.insert(run);
            runId = run.getId();
            log.info("Started offline import run taskId={}, runId={}, ownerId={}", id, runId, ownerId);

            if (importType == null || dataSource == null) {
                throw new IllegalStateException(importType == null ? "导入类型无效" : "目标数据源不存在");
            }
            if (!importType.isDatapack() && dataSource.getDbType() != importType.getTargetDbType()) {
                throw new IllegalStateException("任务目标数据源类型已变更，与导入类型不匹配");
            }
            String failure = importType.isDatapack()
                    ? runDatapackImport(task, dataSource, password, executionLog)
                    : importType.isSql()
                    ? runSqlImport(task, importType, dataSource, options, executionLog)
                    : runDmpImport(task, importType, dataSource, options, executionLog);
            boolean success = failure == null;
            executionLog.line(success ? "任务执行成功" : "任务执行失败: " + failure);
            markTerminated(id, runId, success ? STATUS_SUCCESS : STATUS_FAILED,
                    executionLog.redact(failure), executionLog.snapshot());
        } catch (Exception e) {
            // Native tools and JDBC drivers may include command arguments in
            // exception messages. Keep their raw messages out of application
            // logs; DMP failures expose only a generic exception type.
            log.error("Offline import task {} failed ({})", id, e.getClass().getSimpleName());
            String details = importType != null && importType.isDatapack()
                    ? "数据包密码错误、包内容变化或目标表写入失败"
                    : importType != null && !importType.isSql()
                    ? e.getClass().getSimpleName()
                    : StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            String failure = executionLog.redact("任务执行异常: " + details);
            executionLog.line(failure);
            markTerminated(id, runId, STATUS_FAILED, executionLog.redact(failure), executionLog.snapshot());
        } catch (Error error) {
            log.error("Offline import task {} crashed ({})", id, error.getClass().getSimpleName());
            markTerminated(id, runId, STATUS_FAILED,
                    "任务执行异常: " + error.getClass().getSimpleName(), executionLog.snapshot());
            throw error;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private String runDatapackImport(
            OfflineImportTask task,
            DataSource dataSource,
            char[] password,
            ExecutionLogBuffer executionLog) throws Exception {
        FileResource resource = requireWorkerResource(task.getFileResourceId(), task.getCreateUserId());
        long size = resource.getSize() == null ? 0L : resource.getSize();
        if (size <= 0 || size > offlineImportProperties.getDatapackMaxFileBytes()) {
            return "数据包为空或超过大小上限";
        }
        if (StringUtils.isBlank(task.getDatapackConfigJson())) {
            return "数据包字段映射配置缺失";
        }
        if (!tryAcquireDatapackMemoryBudget("task execution")) {
            return "当前已有数据包正在校验或导入，请稍后重试";
        }
        byte[] encrypted = null;
        try {
            encrypted = readDatapackResource(resource);
            DatapackPackageInspector.Inspection inspection = datapackPackageInspector.inspect(encrypted, password);
            OfflineDatapackTaskConfig config = JSONUtils.parseObject(
                    task.getDatapackConfigJson(), OfflineDatapackTaskConfig.class);
            if (config == null || !inspection.fingerprint().equalsIgnoreCase(config.getPackageFingerprint())) {
                return "数据包内容已变化，请重新检查并保存配置";
            }
            DatapackPackageInspector.FormSummary selectedForm = inspection.forms().stream()
                    .filter(form -> form.formCode().equals(config.getFormCode()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("所选表单不属于当前数据包"));
            int total = selectedForm.recordCount();
            executionLog.line("加密数据包验证通过，类型 " + inspection.packageType()
                    + "，所选表单 " + config.getFormCode() + "，记录 " + total + " 条");
            updateProgress(task.getId(), total, 0, 0);

            BaseConnectionParam connectionParam = targetConnectionParam(dataSource, task.getTargetDatabase());
            registerConnectionSecrets(executionLog, connectionParam.getPassword());
            try (Connection connection = DataSourceUtils.getDatasourceProcessor(dataSource.getDbType())
                    .getConnectionManager().getConnection(connectionParam)) {
                int inserted = DatapackImportRunner.run(connection, task.getTargetSchema(), config, inspection,
                        executed -> updateProgress(task.getId(), total, executed, 0));
                updateProgress(task.getId(), total, inserted, 0);
                executionLog.line("已追加写入 " + inserted + " 条记录；未创建或覆盖目标表");
            }
            return null;
        } finally {
            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
            DATAPACK_MEMORY_BUDGET.release();
        }
    }

    private String runSqlImport(
            OfflineImportTask task,
            OfflineImportType importType,
            DataSource dataSource,
            OfflineImportOptions options,
            ExecutionLogBuffer executionLog) throws Exception {
        FileResource resource = requireWorkerResource(task.getFileResourceId(), task.getCreateUserId());
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

        byte[] bytes = readFileResource(resource, Math.min(maxBytes, heapLimit));
        String text = decode(bytes, options.getEncoding());
        executionLog.line("已读取 SQL 文件 " + task.getFileName()
                + "（" + bytes.length + " 字节，编码 " + options.getEncoding() + "）");
        if (StringUtils.isNotBlank(task.getTargetDatabase())) {
            executionLog.line("目标数据库: " + task.getTargetDatabase());
        }

        SqlImportPlan.Plan plan = SqlImportPlan.prepare(
                text, dataSource.getDbType(), options.getSourceSchema(), task.getTargetSchema());
        List<SqlScriptSplitter.Statement> statements = plan.statements();
        executionLog.line("预检通过，解析得到 " + statements.size() + " 条导入语句"
                + (plan.ignoredMetadata() == 0 ? "" : "，忽略 " + plan.ignoredMetadata() + " 条导出元数据"));
        updateProgress(task.getId(), statements.size(), 0, 0);

        String connectionParamsJson = dataSource.getConnectionParams();
        if (StringUtils.isNotBlank(task.getTargetDatabase())) {
            // 使用已保存的连接凭据重建 JDBC 参数，只覆盖用户选择的目标数据库。
            var connectionParams = JSONUtils.parseObject(connectionParamsJson);
            if (connectionParams == null) {
                throw new IllegalStateException("目标数据源连接参数无效");
            }
            connectionParams.put("database", task.getTargetDatabase());
            connectionParamsJson = JSONUtils.toJsonString(connectionParams);
        }
        BaseConnectionParam connectionParam = (BaseConnectionParam) DataSourceUtils.buildConnectionParams(
                dataSource.getDbType(), connectionParamsJson);
        registerConnectionSecrets(executionLog, connectionParam.getPassword());
        try (Connection connection = DataSourceUtils.getDatasourceProcessor(dataSource.getDbType())
                .getConnectionManager()
                .getConnection(connectionParam)) {
            connection.setAutoCommit(true);
            SqlScriptImportRunner runner = new SqlScriptImportRunner(
                    connection, executionLog, offlineImportProperties.getSqlStatementTimeoutSeconds());
            runner.verifyTimeoutSupport();
            if (statements.stream().anyMatch(SqlScriptSplitter.Statement::isCopy)) {
                runner.verifyCopySupport();
            }

            String effectiveTarget = StringUtils.trimToNull(
                    StringUtils.defaultIfBlank(task.getTargetSchema(), options.getSourceSchema()));
            ensureTargetSchema(connection, dataSource.getDbType(), effectiveTarget,
                    offlineImportProperties.getSqlStatementTimeoutSeconds(), executionLog);
            runner.runPrelude(schemaPrelude(dataSource.getDbType(), effectiveTarget));

            AtomicLong lastFlush = new AtomicLong(System.currentTimeMillis());
            int[] counters = new int[2];
            String abort = runner.executePlan(statements, Boolean.TRUE.equals(options.getStopOnError()),
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
                .resolve("task-" + task.getId() + "-" + UUID.randomUUID());
        Files.createDirectories(workDir);
        try {
            String safeName = task.getFileName().replaceAll("[/\\\\]", "_");
            Path dmpFile = workDir.resolve(safeName);
            Path toolLogFile = workDir.resolve(safeName + ".log");
            // Stream straight to disk: DMP backups may be far larger than the heap.
            FileResource dmpResource = requireWorkerResource(task.getFileResourceId(), task.getCreateUserId());
            long size = dmpResource.getSize() == null ? 0L : dmpResource.getSize();
            long maxBytes = offlineImportProperties.getDmpMaxFileBytes();
            if (size > maxBytes) {
                return "DMP 文件超过大小上限 " + maxBytes + " 字节";
            }
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(dmpFile)) {
                storageProvider.download(dmpResource.getObjectKey(), output);
            }
            executionLog.line("已下载 DMP 文件 " + task.getFileName() + "，调用 " + toolName + " 导入");

            BaseConnectionParam param = (BaseConnectionParam) DataSourceUtils.buildConnectionParams(
                    dataSource.getDbType(), dataSource.getConnectionParams());
            if (importType == OfflineImportType.KINGBASE_DMP
                    && StringUtils.isNotBlank(task.getTargetDatabase())) {
                // Older Kingbase DMP tasks have no explicit destination; keep their datasource default.
                param = targetConnectionParam(dataSource, task.getTargetDatabase());
            }
            if (importType == OfflineImportType.KINGBASE_DMP) {
                executionLog.line("目标数据库: " + param.getDatabase());
            } else {
                executionLog.line("达梦目标数据库实例由所选数据源连接确定");
            }
            if (StringUtils.isNotBlank(task.getTargetSchema())) {
                // Keep already-saved DMP mappings working until the task is edited and saved again.
                executionLog.line("检测到历史目标 Schema 配置，按任务已保存的还原配置执行");
            }
            for (String secret : DmpArchivePreflight.secretVariants(param.getPassword())) {
                executionLog.addSecret(secret);
            }

            DmpProcessRunner processRunner = new DmpProcessRunner();
            Charset processOutputCharset = importType == OfflineImportType.DAMENG_DMP
                    ? Charset.forName("GB18030")
                    : StandardCharsets.UTF_8;
            String selectedSourceSchema = DmpArchivePreflight.selectedSourceSchema(
                    options.getSourceSchema(), task.getTargetSchema());
            Path preflightLogFile = workDir.resolve(safeName + ".preflight.log");
            boolean sourceSchemaFound = selectedSourceSchema == null;
            DmpCommandBuilder.DmpCommand preflightCommand;
            if (importType == OfflineImportType.DAMENG_DMP) {
                preflightCommand = DmpCommandBuilder.damengShow(
                        toolPath, param.getHost(), param.getPort(), param.getUser(), param.getPassword(),
                        dmpFile, preflightLogFile);
            } else {
                preflightCommand = DmpCommandBuilder.kingbaseList(toolPath, dmpFile, selectedSourceSchema);
            }
            AtomicBoolean matchedSourceSchema = new AtomicBoolean(sourceSchemaFound);
            java.util.function.Consumer<String> preflightObserver = importType == OfflineImportType.DAMENG_DMP
                    ? line -> {
                        if (DmpArchivePreflight.damengSchemaEntry(line, selectedSourceSchema)) {
                            matchedSourceSchema.set(true);
                        }
                    }
                    : null;
            DmpProcessRunner.ProcessResult preflightResult = processRunner.run(
                    preflightCommand, preflightCommand.environment(), workDir,
                    offlineImportProperties.getProcessTimeoutMinutes(), executionLog,
                    preflightObserver, processOutputCharset);
            if (importType == OfflineImportType.DAMENG_DMP) {
                appendToolLogFile(preflightLogFile, executionLog, processOutputCharset);
            }
            if (!preflightResult.success()) {
                return "DMP 文件原厂预检失败，源 Schema 未匹配或文件格式无效，已在还原前终止"
                        + (preflightResult.timedOut() ? "（预检超时）" : "");
            }
            if (importType == OfflineImportType.DAMENG_DMP && !matchedSourceSchema.get()) {
                return "DMP 文件中未找到指定源 Schema，已在还原前终止";
            }

            if (importType == OfflineImportType.KINGBASE_DMP) {
                ensureKingbaseTargetSchema(dataSource, param, selectedTargetSchema(
                        options.getSourceSchema(), task.getTargetSchema()));
            }

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
                        param.getUser(), param.getPassword(), dmpFile,
                        options.getSourceSchema(), task.getTargetSchema());
            }
            executionLog.line("执行命令: " + DmpCommandBuilder.mask(command, param.getPassword()));

            DmpProcessRunner.ProcessResult result = processRunner.run(
                    command, command.environment(), workDir,
                    offlineImportProperties.getProcessTimeoutMinutes(), executionLog, null, processOutputCharset);
            appendToolLogFile(toolLogFile, executionLog, processOutputCharset);
            return result.success()
                    ? null
                    : toolName + " 退出码 " + result.exitCode() + "，详见执行日志"
                            + (result.timedOut() ? "（已超时强制终止）" : "");
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void appendToolLogFile(Path toolLogFile, ExecutionLogBuffer executionLog, Charset charset) {
        try {
            if (!Files.exists(toolLogFile)) {
                return;
            }
            String content = readTail(toolLogFile, 8 * 1024, charset);
            if (StringUtils.isNotBlank(content)) {
                executionLog.line("工具日志尾部:");
                for (String line : content.split("\n", -1)) {
                    executionLog.raw(line);
                }
            }
        } catch (IOException e) {
            log.warn("Read DMP tool log failed ({})", e.getClass().getSimpleName());
        }
    }

    private String readTail(Path file, int maxChars, Charset charset) throws IOException {
        char[] ring = new char[maxChars];
        char[] chunk = new char[4096];
        int next = 0;
        int size = 0;
        try (Reader reader = Files.newBufferedReader(file, charset)) {
            int read;
            while ((read = reader.read(chunk)) >= 0) {
                for (int index = 0; index < read; index++) {
                    ring[next] = chunk[index];
                    next = (next + 1) % maxChars;
                    size = Math.min(size + 1, maxChars);
                }
            }
        }
        StringBuilder tail = new StringBuilder(size);
        int start = (next - size + maxChars) % maxChars;
        for (int index = 0; index < size; index++) {
            tail.append(ring[(start + index) % maxChars]);
        }
        return tail.toString();
    }

    private String selectedTargetSchema(String sourceSchema, String targetSchema) {
        String target = StringUtils.trimToNull(targetSchema);
        return target != null ? target : StringUtils.trimToNull(sourceSchema);
    }

    private void ensureKingbaseTargetSchema(
            DataSource dataSource, BaseConnectionParam connectionParam, String targetSchema) throws Exception {
        String schema = StringUtils.trimToNull(targetSchema);
        if (schema == null) {
            return;
        }
        if (schema.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("目标 Schema 名称不合法");
        }
        String quotedSchema = "\"" + schema.replace("\"", "\"\"") + "\"";
        try (Connection connection = DataSourceUtils.getDatasourceProcessor(dataSource.getDbType())
                     .getConnectionManager().getConnection(connectionParam);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(1, offlineImportProperties.getSqlStatementTimeoutSeconds()));
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + quotedSchema);
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
        String quotedSchema = SqlSchemaMapper.quoteIdentifier(targetSchema.trim());
        if (dbType == DbType.KINGBASE) {
            prelude.add("SET search_path TO " + quotedSchema);
        } else if (dbType == DbType.DAMENG) {
            prelude.add("SET SCHEMA " + quotedSchema);
        }
        return prelude;
    }

    private void ensureTargetSchema(Connection connection, DbType dbType, String targetSchema, int timeoutSeconds,
            ExecutionLogBuffer executionLog) throws java.sql.SQLException {
        if (StringUtils.isBlank(targetSchema)) {
            return;
        }
        String expected = targetSchema.trim();
        if (dbType == DbType.KINGBASE) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(1, timeoutSeconds));
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + SqlSchemaMapper.quoteIdentifier(expected));
            }
            executionLog.line("已确保目标 Schema " + expected + " 存在");
            return;
        }

        boolean exists = false;
        try (ResultSet schemas = connection.getMetaData().getSchemas()) {
            while (schemas.next()) {
                if (expected.equals(schemas.getString("TABLE_SCHEM"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(Math.max(1, timeoutSeconds));
                statement.execute("CREATE SCHEMA " + SqlSchemaMapper.quoteIdentifier(expected));
            }
            executionLog.line("已创建目标 Schema " + expected);
        }
    }

    static String decode(byte[] bytes, String encoding) {
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
        try {
            return charset.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("SQL 文件编码与所选编码不匹配，请选择 UTF-8 或 GBK");
        }
    }

    private void registerConnectionSecrets(ExecutionLogBuffer executionLog, String password) {
        if (StringUtils.isBlank(password)) {
            return;
        }
        executionLog.addSecret(password);
        executionLog.addSecret(password.replace("\"", "\"\""));
        executionLog.addSecret(java.net.URLEncoder.encode(password, StandardCharsets.UTF_8));
    }

    private FileResource requireWorkerResource(Long fileResourceId, Integer taskOwnerId) {
        FileResource resource = fileResourceId == null ? null : fileResourceDao.queryById(fileResourceId);
        if (resource == null || taskOwnerId == null || !taskOwnerId.equals(resource.getOwnerId())
                || !"READY".equalsIgnoreCase(resource.getStatus())
                || !"FILE".equalsIgnoreCase(resource.getResourceType())) {
            throw new IllegalStateException("导入文件资源不存在或不可用");
        }
        return resource;
    }

    private byte[] readFileResource(FileResource resource, long maxBytes) {
        try {
            LimitedByteArrayOutputStream output = new LimitedByteArrayOutputStream(maxBytes);
            storageProvider.download(resource.getObjectKey(), output);
            return output.toByteArray();
        } catch (FileTooLargeException e) {
            throw new IllegalStateException("SQL 文件实际内容超过大小上限 " + maxBytes + " 字节");
        } catch (IOException e) {
            throw new IllegalStateException("读取 SQL 文件资源失败");
        }
    }

    private boolean tryAcquireDatapackMemoryBudget(String operation) {
        if (DATAPACK_MEMORY_BUDGET.tryAcquire()) {
            return true;
        }
        log.warn("Rejected datapack {} because the single-package memory budget is occupied", operation);
        return false;
    }

    private byte[] readDatapackResource(FileResource resource) {
        return readDatapackResource(resource, offlineImportProperties.getDatapackMaxFileBytes());
    }

    private byte[] readDatapackResource(FileResource resource, long maxBytes) {
        try {
            LimitedByteArrayOutputStream output = new LimitedByteArrayOutputStream(maxBytes);
            storageProvider.download(resource.getObjectKey(), output);
            return output.toByteArray();
        } catch (FileTooLargeException e) {
            throw new IllegalStateException("数据包实际内容超过大小上限");
        } catch (IOException e) {
            throw new IllegalStateException("读取数据包资源失败");
        }
    }

    private static final class FileTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class LimitedByteArrayOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final long maxBytes;

        private LimitedByteArrayOutputStream(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(int length) throws FileTooLargeException {
            if (length < 0 || (long) delegate.size() + length > maxBytes) {
                throw new FileTooLargeException();
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private void updateProgress(Long taskId, int total, int executed, int failed) {
        offlineImportTaskDao.updateProgressIfRunning(taskId, total, executed, failed);
    }

    private void markTerminated(Long taskId, Long runId, String status, String errorMessage, String executionLog) {
        Date endTime = new Date();
        boolean updated = offlineImportTaskRunCompletionService.finish(
                taskId,
                runId,
                status,
                OfflineImportLogRedactor.mask(StringUtils.abbreviate(errorMessage, 2000)),
                executionLog, endTime);
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
        vo.setTargetDatabase(entity.getTargetDatabase());
        vo.setTargetSchema(entity.getTargetSchema());
        vo.setOptions(StringUtils.isBlank(entity.getOptionsJson())
                ? null
                : JSONUtils.parseObject(entity.getOptionsJson(), OfflineImportOptions.class));
        vo.setDatapackConfig(StringUtils.isBlank(entity.getDatapackConfigJson())
                ? null
                : JSONUtils.parseObject(entity.getDatapackConfigJson(), OfflineDatapackTaskConfig.class));
        vo.setScheduleConfig(StringUtils.isBlank(entity.getScheduleConfigJson())
                ? null
                : JSONUtils.parseObject(entity.getScheduleConfigJson(), new TypeReference<Map<String, Object>>() {}));
        vo.setReleaseState(entity.getReleaseState());
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

    private OfflineImportTaskRunVO toRunVO(OfflineImportTaskRun entity) {
        OfflineImportTaskRunVO vo = new OfflineImportTaskRunVO();
        vo.setId(entity.getId());
        vo.setTaskName(entity.getTaskName());
        vo.setStatus(entity.getStatus());
        vo.setTotalStatements(entity.getTotalStatements());
        vo.setExecutedStatements(entity.getExecutedStatements());
        vo.setFailedStatements(entity.getFailedStatements());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setExecutionLog(entity.getExecutionLog());
        vo.setStartTime(entity.getStartTime());
        vo.setEndTime(entity.getEndTime());
        return vo;
    }

    private static final class DatapackCsvMultipartFile implements MultipartFile {
        private final String fileName;
        private final byte[] content;

        private DatapackCsvMultipartFile(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            int separator = fileName.lastIndexOf('/');
            return separator < 0 ? fileName : fileName.substring(separator + 1);
        }

        @Override
        public String getContentType() {
            return "text/csv; charset=UTF-8";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return Arrays.copyOf(content, content.length);
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File destination) throws IOException {
            Files.write(destination.toPath(), content);
        }
    }

    private record ValidatedImportConfig(
            OfflineImportType importType,
            String fileName,
            OfflineImportOptions options,
            DataSource target,
            OfflineDatapackTaskConfig datapackConfig) {
    }
}
