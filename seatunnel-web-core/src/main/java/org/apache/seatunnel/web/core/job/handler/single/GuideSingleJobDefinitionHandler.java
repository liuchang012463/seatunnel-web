package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.TaskExecutionMode;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.core.time.IncrementalConfigResolver;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class GuideSingleJobDefinitionHandler implements JobDefinitionModeHandler {

    private static final DateTimeFormatter STRICT_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);

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
        if (schedule == null) {
            throw new IllegalArgumentException("单表增量任务必须配置定时调度");
        }
        if (schedule.resolveExecutionMode() != TaskExecutionMode.AUTO
                || StringUtils.isBlank(schedule.getCronExpression())) {
            throw new IllegalArgumentException("单表增量任务必须使用自动调度并配置有效 Cron");
        }

        Map<String, Object> source = findConfig(workflow, "source");
        Map<String, Object> sink = findConfig(workflow, "sink");
        if (source.isEmpty() || sink.isEmpty()) {
            throw new IllegalArgumentException("单表增量任务必须包含一个来源和一个目标");
        }

        JobScheduleConfig.IncrementalConfig incremental =
                IncrementalConfigResolver.resolve(workflow, schedule);
        if (incremental == null || !Boolean.TRUE.equals(incremental.getEnabled())) {
            throw new IllegalArgumentException("单表增量任务必须启用增量微批配置");
        }
        String fieldName = incremental.getWatermarkColumn();
        String startValue = incremental.getInitialWatermark();
        if (IncrementalConfigResolver.hasCanonicalSourceConfig(workflow)) {
            Map<String, Object> sourceIncremental =
                    IncrementalConfigResolver.sourceIncrementalConfig(workflow);
            if (!Boolean.TRUE.equals(booleanValue(sourceIncremental.get("enabled")))) {
                throw new IllegalArgumentException("增量配置未启用");
            }
            fieldName = firstNonBlank(sourceIncremental.get("fieldName"));
            startValue = firstNonBlank(sourceIncremental.get("startValue"));
            if (StringUtils.isBlank(fieldName)) {
                throw new IllegalArgumentException("请选择增量识别字段");
            }
            if (StringUtils.isBlank(startValue)) {
                throw new IllegalArgumentException("请选择增量起始值");
            }
            parseStrictDateTime(startValue, "增量起始值");
            validateTemporalField(workflow, fieldName);
        } else {
            parseDateTime(startValue, "增量起始值");
        }
        if (!isIdentifier(fieldName)) {
            throw new IllegalArgumentException("增量识别字段必须是合法字段名");
        }
        int safetyDelay = valueOrDefault(incremental.getSafetyDelaySeconds(), 0);
        int overlap = valueOrDefault(incremental.getOverlapSeconds(), 0);
        int maxWindow = valueOrDefault(incremental.getMaxWindowSeconds(), 1800);
        String sourceDbType = firstNonBlank(source.get("dbType"), source.get("sourceDbType"));
        if (!JDBC_DB_TYPES.contains(sourceDbType.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("单表增量任务来源必须是 JDBC 数据源");
        }
        if (safetyDelay < 0 || overlap < 0 || maxWindow <= 0 || overlap >= maxWindow) {
            throw new IllegalArgumentException("增量任务运行参数不合法");
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

    private void validateTemporalField(Map<String, Object> workflow, String fieldName) {
        Map<String, Object> node = WorkflowNodeHelper.findFirstNodeByType(workflow, "source");
        Map<String, Object> data = WorkflowNodeHelper.safeMap(node.get("data"));
        Map<String, Object> meta = WorkflowNodeHelper.safeMap(data.get("meta"));
        Object rawSchema = meta.get("outputSchema");
        if (!(rawSchema instanceof List) || ((List<?>) rawSchema).isEmpty()) {
            throw new IllegalArgumentException("请先完成源端字段解析，再选择增量识别字段");
        }

        for (Object rawColumn : (List<?>) rawSchema) {
            if (!(rawColumn instanceof Map)) {
                continue;
            }
            Map<?, ?> column = (Map<?, ?>) rawColumn;
            String name = firstNonBlank(
                    column.get("originFieldName"),
                    column.get("fieldName"),
                    column.get("name")
            );
            if (!fieldName.equalsIgnoreCase(name)) {
                continue;
            }
            String type = firstNonBlank(
                    column.get("type"),
                    column.get("fieldType"),
                    column.get("dataType")
            ).toUpperCase(Locale.ROOT);
            if (!type.contains("DATE") && !type.contains("TIME") && !type.contains("TIMESTAMP")) {
                throw new IllegalArgumentException("增量识别字段必须是 DATE、DATETIME 或 TIMESTAMP 等时间类型");
            }
            return;
        }
        throw new IllegalArgumentException("增量识别字段不存在于当前字段解析结果");
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

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private void parseStrictDateTime(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        try {
            LocalDateTime.parse(value.trim(), STRICT_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + "格式必须是 yyyy-MM-dd HH:mm:ss");
        }
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
