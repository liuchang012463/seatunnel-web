package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GuideSingleJobDefinitionHandler implements JobDefinitionModeHandler {

    private final GuideSingleWorkflowValidator workflowValidator;
    private final GuideSingleWorkflowAnalyzer workflowAnalyzer;
    private final GuideSingleHoconBuildService hoconBuildService;

    public GuideSingleJobDefinitionHandler(
            GuideSingleWorkflowValidator workflowValidator,
            GuideSingleWorkflowAnalyzer workflowAnalyzer,
            GuideSingleHoconBuildService hoconBuildService) {
        this.workflowValidator = workflowValidator;
        this.workflowAnalyzer = workflowAnalyzer;
        this.hoconBuildService = hoconBuildService;
    }

    @Override
    public boolean supports(JobDefinitionMode mode) {
        return JobDefinitionMode.GUIDE_SINGLE == mode
                || JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL == mode
                || JobDefinitionMode.FILE_SYNC == mode;
    }

    @Override
    public void validate(JobDefinitionSaveCommand command) {
        GuideSingleJobContentCommand cmd = cast(command);
        workflowValidator.validate(cmd.getWorkflow());
        if (command.getMode() == JobDefinitionMode.FILE_SYNC) {
            validateFileSync(cmd.getWorkflow());
        }
        if (command.getMode() == JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL) {
            validateIncremental(cmd.getWorkflow(), command);
        }
    }

    @Override
    public JobDefinitionAnalysisResult analyze(JobDefinitionSaveCommand command) {
        GuideSingleJobContentCommand cmd = cast(command);
        return workflowAnalyzer.analyze(cmd.getWorkflow());
    }

    @Override
    public String serializeDefinition(JobDefinitionSaveCommand command) {
        GuideSingleJobContentCommand cmd = cast(command);
        return JSONUtils.toJsonString(cmd.getWorkflow());
    }

    @Override
    public String buildHoconConfig(JobDefinitionSaveCommand command) {
        GuideSingleJobContentCommand cmd = cast(command);
        return hoconBuildService.build(cmd.getWorkflow(), command);
    }

    private GuideSingleJobContentCommand cast(JobDefinitionSaveCommand command) {
        if (!(command instanceof GuideSingleJobContentCommand)) {
            throw new IllegalArgumentException("command must implement GuideSingleJobContentCommand");
        }
        return (GuideSingleJobContentCommand) command;
    }

    @SuppressWarnings("unchecked")
    private void validateFileSync(Map<String, Object> workflow) {
        Object rawNodes = workflow.get("nodes");
        if (!(rawNodes instanceof List)) {
            throw new IllegalArgumentException("FILE_SYNC workflow nodes are required");
        }
        Map<String, Object> source = null;
        Map<String, Object> sink = null;
        for (Object rawNode : (List<?>) rawNodes) {
            if (!(rawNode instanceof Map)) { continue; }
            Object rawData = ((Map<?, ?>) rawNode).get("data");
            if (!(rawData instanceof Map)) { continue; }
            Map<String, Object> data = (Map<String, Object>) rawData;
            Object rawConfig = data.get("config");
            Map<String, Object> config = rawConfig instanceof Map ? (Map<String, Object>) rawConfig : data;
            if ("source".equals(String.valueOf(data.get("nodeType")))) { source = config; }
            if ("sink".equals(String.valueOf(data.get("nodeType")))) { sink = config; }
        }
        if (source == null || sink == null) {
            throw new IllegalArgumentException("FILE_SYNC requires exactly one source and one sink");
        }
        requireFileDbType(source, "source");
        requireFileDbType(sink, "sink");
        if ("INCREMENTAL".equalsIgnoreCase(String.valueOf(source.get("syncType")))) {
            requireIncrementalDbType(source, "source");
            requireIncrementalDbType(sink, "sink");
            if (!String.valueOf(source.get("dataSourceId"))
                    .equals(String.valueOf(sink.get("dataSourceId")))) {
                throw new IllegalArgumentException(
                        "FILE_SYNC incremental mode requires the same source and target datasource");
            }
        }
    }

    private void requireFileDbType(Map<String, Object> config, String role) {
        String dbType = String.valueOf(config.get("dbType"));
        if (!"FTP".equalsIgnoreCase(dbType)
                && !"SFTP".equalsIgnoreCase(dbType)
                && !"S3".equalsIgnoreCase(dbType)
                && !"MINIO".equalsIgnoreCase(dbType)) {
            throw new IllegalArgumentException(
                    "FILE_SYNC " + role + " dbType must be FTP, SFTP, S3, or MINIO");
        }
    }

    private void requireIncrementalDbType(Map<String, Object> config, String role) {
        String dbType = String.valueOf(config.get("dbType"));
        if (!"FTP".equalsIgnoreCase(dbType) && !"SFTP".equalsIgnoreCase(dbType)) {
            throw new IllegalArgumentException(
                    "SeaTunnel 2.3.13 FILE_SYNC incremental mode supports FTP/SFTP only; "
                            + role + " dbType=" + dbType);
        }
    }

    private void validateIncremental(Map<String, Object> workflow,
                                     JobDefinitionSaveCommand command) {
        if (!(command instanceof org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand)) {
            throw new IllegalArgumentException("GUIDE_SINGLE_INCREMENTAL must be a batch job");
        }

        JobScheduleConfig schedule =
                ((org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand) command).getSchedule();
        JobScheduleConfig.IncrementalConfig incremental = schedule == null ? null : schedule.getIncremental();
        if (incremental == null || !Boolean.TRUE.equals(incremental.getEnabled())) {
            throw new IllegalArgumentException("单表增量任务必须启用增量微批配置");
        }
        if (!isIdentifier(incremental.getWatermarkColumn())) {
            throw new IllegalArgumentException("水位字段必须是合法字段名");
        }
        parseDateTime(incremental.getInitialWatermark(), "初始水位");
        int safetyDelay = valueOrDefault(incremental.getSafetyDelaySeconds(), 120);
        int overlap = valueOrDefault(incremental.getOverlapSeconds(), 60);
        int maxWindow = valueOrDefault(incremental.getMaxWindowSeconds(), 1800);
        if (safetyDelay < 0 || overlap < 0 || maxWindow <= 0 || overlap >= maxWindow) {
            throw new IllegalArgumentException("安全延迟、重叠窗口和最大窗口参数不合法");
        }

        Map<String, Object> source = findConfig(workflow, "source");
        Map<String, Object> sink = findConfig(workflow, "sink");
        if (source.isEmpty() || sink.isEmpty()) {
            throw new IllegalArgumentException("单表增量任务必须包含一个来源和一个目标");
        }
        String sourceDbType = firstNonBlank(source.get("dbType"), source.get("sourceDbType"));
        if (!JDBC_DB_TYPES.contains(sourceDbType.toUpperCase())) {
            throw new IllegalArgumentException("单表增量任务来源必须是 JDBC 数据源");
        }
        String sql = firstNonBlank(source.get("sql"), source.get("query"));
        String table = firstNonBlank(source.get("table"), source.get("table_path"));
        if (sql.isEmpty() && table.isEmpty()) {
            throw new IllegalArgumentException("增量来源必须配置表或 SQL");
        }
        if (!sql.isEmpty()
                && (!sql.contains("${window_start}") || !sql.contains("${window_end}"))) {
            throw new IllegalArgumentException("增量 SQL 必须包含 ${window_start} 和 ${window_end}");
        }
        String writeMode = firstNonBlank(sink.get("writeMode"), sink.get("data_save_mode"));
        String primaryKey = firstNonBlank(sink.get("primaryKey"), sink.get("primary_keys"));
        if (!"upsert".equalsIgnoreCase(writeMode)
                || primaryKey.isEmpty()) {
            throw new IllegalArgumentException("增量任务目标必须配置 Upsert 写入模式和主键");
        }
    }

    private static final Set<String> JDBC_DB_TYPES = new HashSet<>(Set.of(
            "JDBC", "MYSQL", "ORACLE", "POSTGRE_SQL", "DORIS", "KINGBASE", "DAMENG", "H2"));

    private Map<String, Object> findConfig(Map<String, Object> workflow, String nodeType) {
        Map<String, Object> node = WorkflowNodeHelper.findFirstNodeByType(workflow, nodeType);
        Map<String, Object> data = WorkflowNodeHelper.safeMap(node.get("data"));
        Map<String, Object> config = WorkflowNodeHelper.safeMap(data.get("config"));
        return config.isEmpty() ? data : config;
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private boolean isIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private void parseDateTime(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        try {
            LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            try {
                LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]"));
            } catch (DateTimeParseException ignored) {
                throw new IllegalArgumentException(field + "格式必须是 yyyy-MM-dd HH:mm:ss");
            }
        }
    }
}
