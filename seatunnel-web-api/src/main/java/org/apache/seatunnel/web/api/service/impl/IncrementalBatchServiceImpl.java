package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.api.service.IncrementalBatchExecution;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.time.IncrementalConfigResolver;
import org.apache.seatunnel.web.core.time.IncrementalSqlRenderer;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.IncrementalBatchControl;
import org.apache.seatunnel.web.dao.entity.IncrementalBatchRecord;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.IncrementalBatchControlDao;
import org.apache.seatunnel.web.dao.repository.IncrementalBatchRecordDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionContentDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.JobScheduleDao;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates the control table, fixed batch windows, and SeaTunnel results.
 * The source database clock is used so application clock skew cannot silently
 * move a window past newly committed source rows.
 */
@Service
@Slf4j
public class IncrementalBatchServiceImpl implements IncrementalBatchService {

    private static final String RUNNING = "RUNNING";
    private static final String FAILED = "FAILED";
    private static final String SUCCESS = "SUCCESS";
    private static final String READY = "READY";

    @Resource
    private JobDefinitionDao jobDefinitionDao;

    @Resource
    private JobDefinitionContentDao jobDefinitionContentDao;

    @Resource
    private JobScheduleDao jobScheduleDao;

    @Resource
    private JobInstanceDao jobInstanceDao;

    @Resource
    private DataSourceDao dataSourceDao;

    @Resource
    private IncrementalBatchControlDao controlDao;

    @Resource
    private IncrementalBatchRecordDao recordDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IncrementalBatchExecution prepare(Long jobDefinitionId) {
        JobDefinitionEntity definition = jobDefinitionDao.queryById(jobDefinitionId);
        if (definition == null || definition.getMode() != JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL) {
            return null;
        }

        if (jobInstanceDao.existsRunningInstance(jobDefinitionId)) {
            return skipped("existing running job instance");
        }

        IncrementalBatchControl control = controlDao.queryByDefinitionIdForUpdate(jobDefinitionId);
        JobScheduleConfig config = loadScheduleConfig(jobDefinitionId);
        IncrementalConfigResolver.resolve(loadWorkflow(jobDefinitionId), config);
        JobScheduleConfig.IncrementalConfig incremental = config.getIncremental();
        validateRuntimeConfig(incremental);
        boolean bootstrap = control == null;

        IncrementalBatchRecord running = recordDao.queryRunningByDefinitionId(jobDefinitionId);
        if (running != null) {
            return skipped("incremental batch is already running");
        }

        IncrementalBatchRecord retry = control == null
                ? null : recordDao.queryLatestFailedByDefinitionId(jobDefinitionId);
        if (control != null && retry != null
                && toLocalDateTime(retry.getWindowStart())
                        .equals(toLocalDateTime(control.getCommittedWatermark()))) {
            return reopenFailedBatch(control, retry);
        }

        LocalDateTime start = bootstrap
                ? parseDateTime(incremental.getInitialWatermark())
                : toLocalDateTime(control.getCommittedWatermark());
        LocalDateTime sourceNow = querySourceNow(definition);
        int safetyDelay = valueOrDefault(incremental.getSafetyDelaySeconds(), 0);
        int maxWindow = valueOrDefault(incremental.getMaxWindowSeconds(), 1800);
        int overlap = valueOrDefault(incremental.getOverlapSeconds(), 0);
        IncrementalBatchWindowResolver.Window window = IncrementalBatchWindowResolver.resolve(
                bootstrap, start, sourceNow, safetyDelay, overlap, maxWindow);
        if (window == null) {
            return skipped("no safely committed source data is available");
        }

        if (bootstrap) {
            control = createControl(jobDefinitionId, start);
        }

        String batchId = buildBatchId(jobDefinitionId, window.start(), window.end());
        Date now = new Date();

        IncrementalBatchRecord record = new IncrementalBatchRecord();
        record.setBatchId(batchId);
        record.setJobDefinitionId(jobDefinitionId);
        record.setWindowStart(toDate(window.start()));
        record.setWindowEnd(toDate(window.end()));
        record.setQueryStart(toDate(window.queryStart()));
        record.setBatchStatus(RUNNING);
        record.setRetryCount(0);
        record.setStartedAt(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        recordDao.insert(record);
        controlDao.updateStatus(control.getId(), RUNNING, now);

        return execution(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindInstance(String batchId, Long jobInstanceId) {
        if (StringUtils.isBlank(batchId) || jobInstanceId == null) {
            return;
        }
        IncrementalBatchRecord record = recordDao.queryById(batchId);
        if (record == null) {
            throw new IllegalArgumentException("incremental batch record not found: " + batchId);
        }
        record.setJobInstanceId(jobInstanceId);
        record.setUpdateTime(new Date());
        recordDao.updateById(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSubmitFailure(Long jobInstanceId, Throwable error) {
        IncrementalBatchRecord record = recordDao.queryByInstanceId(jobInstanceId);
        if (record == null || SUCCESS.equals(record.getBatchStatus())) {
            return;
        }
        markFailed(record, message(error));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markBatchFailure(String batchId, Throwable error) {
        if (StringUtils.isBlank(batchId)) {
            return;
        }
        IncrementalBatchRecord record = recordDao.queryById(batchId);
        if (record != null && !SUCCESS.equals(record.getBatchStatus())) {
            markFailed(record, message(error));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleJobResult(Long jobInstanceId, JobStatus status, String errorMessage) {
        IncrementalBatchRecord record = recordDao.queryByInstanceId(jobInstanceId);
        if (record == null || SUCCESS.equals(record.getBatchStatus())) {
            return;
        }

        if (JobStatus.FINISHED.equals(status)) {
            commitSuccess(record);
        } else {
            markFailed(record, StringUtils.defaultIfBlank(errorMessage, "SeaTunnel job did not finish successfully"));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeByDefinitionId(Long jobDefinitionId) {
        if (jobDefinitionId == null) {
            return;
        }
        recordDao.deleteByDefinitionId(jobDefinitionId);
        controlDao.deleteByDefinitionId(jobDefinitionId);
    }

    private IncrementalBatchExecution reopenFailedBatch(IncrementalBatchControl control,
                                                        IncrementalBatchRecord record) {
        Date now = new Date();
        record.setBatchStatus(RUNNING);
        record.setRetryCount(valueOrDefault(record.getRetryCount(), 0) + 1);
        record.setStartedAt(now);
        record.setFinishedAt(null);
        record.setErrorMessage(null);
        record.setUpdateTime(now);
        recordDao.updateById(record);
        controlDao.updateStatus(control.getId(), RUNNING, now);
        return execution(record);
    }

    private void commitSuccess(IncrementalBatchRecord record) {
        IncrementalBatchControl control = controlDao.queryByDefinitionIdForUpdate(record.getJobDefinitionId());
        if (control == null) {
            throw new IllegalStateException("incremental control record not found: " + record.getJobDefinitionId());
        }
        if (toLocalDateTime(control.getCommittedWatermark()).isAfter(toLocalDateTime(record.getWindowEnd()))) {
            record.setBatchStatus(SUCCESS);
            record.setFinishedAt(new Date());
            record.setUpdateTime(new Date());
            recordDao.updateById(record);
            return;
        }
        if (!toLocalDateTime(control.getCommittedWatermark()).equals(toLocalDateTime(record.getWindowStart()))) {
            throw new IllegalStateException("incremental watermark changed before batch commit, batchId="
                    + record.getBatchId());
        }
        if (!controlDao.updateWatermark(control, record.getWindowEnd(), record.getBatchId())) {
            throw new IllegalStateException("failed to advance incremental watermark, batchId=" + record.getBatchId());
        }
        record.setBatchStatus(SUCCESS);
        record.setFinishedAt(new Date());
        record.setUpdateTime(new Date());
        recordDao.updateById(record);
    }

    private void markFailed(IncrementalBatchRecord record, String errorMessage) {
        Date now = new Date();
        record.setBatchStatus(FAILED);
        record.setFinishedAt(now);
        record.setErrorMessage(StringUtils.abbreviate(errorMessage, 3900));
        record.setUpdateTime(now);
        recordDao.updateById(record);
        IncrementalBatchControl control = controlDao.queryByDefinitionIdForUpdate(record.getJobDefinitionId());
        if (control != null) {
            controlDao.updateStatus(control.getId(), FAILED, now);
        }
    }

    private IncrementalBatchExecution execution(IncrementalBatchRecord record) {
        Map<String, String> params = new HashMap<>();
        params.put("batch_id", record.getBatchId());
        params.put("window_start", IncrementalSqlRenderer.format(toLocalDateTime(record.getWindowStart())));
        params.put("window_end", IncrementalSqlRenderer.format(toLocalDateTime(record.getWindowEnd())));
        params.put("query_start", IncrementalSqlRenderer.format(toLocalDateTime(record.getQueryStart())));
        return IncrementalBatchExecution.builder()
                .skipped(false)
                .batchId(record.getBatchId())
                .runtimeParams(params)
                .build();
    }

    private IncrementalBatchExecution skipped(String reason) {
        log.info("Skip incremental micro-batch: {}", reason);
        return IncrementalBatchExecution.builder().skipped(true).build();
    }

    private IncrementalBatchControl createControl(Long definitionId, LocalDateTime initialWatermark) {
        IncrementalBatchControl control = new IncrementalBatchControl();
        control.initInsert();
        control.setJobDefinitionId(definitionId);
        control.setCommittedWatermark(toDate(initialWatermark));
        control.setTaskStatus(READY);
        control.setVersionNo(0);
        controlDao.insert(control);
        return control;
    }

    private JobScheduleConfig loadScheduleConfig(Long definitionId) {
        JobSchedule schedule = jobScheduleDao.queryByJobDefinitionId(definitionId);
        if (schedule == null || StringUtils.isBlank(schedule.getScheduleConfig())) {
            throw new IllegalArgumentException("增量任务必须配置定时调度");
        }
        try {
            JobScheduleConfig config = JSONUtils.parseObject(schedule.getScheduleConfig(), JobScheduleConfig.class);
            if (config == null) {
                throw new IllegalArgumentException("增量调度配置为空");
            }
            return config;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析增量调度配置失败", e);
        }
    }

    private Map<String, Object> loadWorkflow(Long definitionId) {
        org.apache.seatunnel.web.dao.entity.JobDefinitionContentEntity content =
                jobDefinitionContentDao.queryLatestByJobDefinitionId(definitionId);
        if (content == null || StringUtils.isBlank(content.getDefinitionContent())) {
            throw new IllegalArgumentException("增量任务定义内容为空");
        }
        try {
            Map<String, Object> workflow = JSONUtils.parseObject(
                    content.getDefinitionContent(),
                    new TypeReference<Map<String, Object>>() {}
            );
            if (workflow == null) {
                throw new IllegalArgumentException("增量任务工作流为空");
            }
            return workflow;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析增量任务工作流失败", e);
        }
    }

    private void validateRuntimeConfig(JobScheduleConfig.IncrementalConfig incremental) {
        if (incremental == null || !Boolean.TRUE.equals(incremental.getEnabled())) {
            throw new IllegalArgumentException("incremental configuration is not enabled");
        }
        if (StringUtils.isBlank(incremental.getWatermarkColumn())) {
            throw new IllegalArgumentException("incremental watermark column is empty");
        }
        if (valueOrDefault(incremental.getMaxWindowSeconds(), 1800) <= 0) {
            throw new IllegalArgumentException("incremental max window must be positive");
        }
    }

    private LocalDateTime querySourceNow(JobDefinitionEntity definition) {
        if (definition.getSourceDatasourceId() == null) {
            throw new IllegalArgumentException("incremental source datasource is empty");
        }
        DataSource dataSource = dataSourceDao.queryById(definition.getSourceDatasourceId());
        if (dataSource == null || dataSource.getDbType() == null) {
            throw new IllegalArgumentException("incremental source datasource does not exist");
        }

        DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(dataSource.getDbType());
        if (processor == null) {
            throw new IllegalArgumentException("no processor for incremental source: " + dataSource.getDbType());
        }
        ConnectionParam param = DataSourceUtils.buildConnectionParams(
                dataSource.getDbType(), dataSource.getConnectionParams());
        DataSourceUtils.checkDatasourceParam(param);
        JdbcConnectionProvider provider = processor.getConnectionManager();
        String sql = dataSource.getDbType() == DbType.ORACLE
                ? "SELECT SYSTIMESTAMP FROM dual" : "SELECT CURRENT_TIMESTAMP";

        try (Connection connection = provider.getConnection(param);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException("source database returned no current timestamp");
            }
            return toLocalDateTime(resultSet.getObject(1));
        } catch (Exception e) {
            throw new IllegalStateException("query source database current time failed", e);
        }
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().atStartOfDay();
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).toLocalDateTime();
        }
        if (value instanceof java.time.ZonedDateTime) {
            return ((java.time.ZonedDateTime) value).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace('T', ' ').replaceAll("[+].*$", ""),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]"));
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception ignored) {
            return LocalDateTime.parse(value.trim(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]"));
        }
    }

    private Date toDate(LocalDateTime value) {
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }

    private LocalDateTime toLocalDateTime(Date value) {
        return value.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private String buildBatchId(Long definitionId, LocalDateTime start, LocalDateTime end) {
        String base = "inc_" + definitionId + "_"
                + IncrementalSqlRenderer.format(start).replace("-", "").replace(":", "").replace(".", "")
                + "_" + IncrementalSqlRenderer.format(end).replace("-", "").replace(":", "").replace(".", "");
        return base.length() <= 96 ? base : base.substring(0, 80) + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String message(Throwable error) {
        if (error == null || StringUtils.isBlank(error.getMessage())) {
            return "job submission failed";
        }
        return error.getMessage();
    }
}
