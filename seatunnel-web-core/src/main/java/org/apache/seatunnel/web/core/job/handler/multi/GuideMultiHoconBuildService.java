package org.apache.seatunnel.web.core.job.handler.multi;

import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.builder.HoconConfigBuilder;
import org.apache.seatunnel.web.core.dag.DagGraph;
import org.apache.seatunnel.web.core.job.handler.JobRuntimeContext;
import org.apache.seatunnel.web.core.job.handler.JobRuntimeContextFactory;
import org.apache.seatunnel.web.core.utils.DagUtil;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GuideMultiHoconBuildService {

    private static final String SOURCE_NODE_ID = "guide-multi-source";
    private static final String SINK_NODE_ID = "guide-multi-sink";
    private static final String EDGE_ID = "guide-multi-source-to-sink";

    private static final String NODE_TYPE_SOURCE = "source";
    private static final String NODE_TYPE_SINK = "sink";

    private static final String KEY_MULTI_TABLE = "multiTable";
    private static final String KEY_TABLE_LIST = "table_list";
    private static final String KEY_SOURCE_TABLE_LIST = "source_table_list";
    private static final String KEY_SINK_TABLE_LIST = "sink_table_list";
    private static final String KEY_MATCH_MODE = "matchMode";
    private static final String KEY_MATCH_MODE_UNDERLINE = "match_mode";
    private static final String KEY_SOURCE_TABLE = "sourceTable";
    private static final String KEY_TABLE_KEYWORD = "tableKeyword";

    private static final String MATCH_MODE_CUSTOM = "1";
    private static final String MATCH_MODE_REGEX = "2";
    private static final String MATCH_MODE_EXACT = "3";
    private static final String MATCH_MODE_WHOLE_DATABASE = "4";

    @Resource
    private HoconConfigBuilder hoconConfigBuilder;

    @Resource
    private GuideMultiTableMatchResolver tableMatchResolver;

    @Resource
    private JobRuntimeContextFactory runtimeContextFactory;

    public String build(GuideMultiJobContent content, JobDefinitionSaveCommand command) {
        validateContent(content);

        JobRuntimeContext runtimeContext = runtimeContextFactory.create(command);

        Map<String, Object> workflow = buildWorkflow(content, runtimeContext);

        String dagJson = JSONUtils.toJsonString(workflow);
        if (StringUtils.isBlank(dagJson)) {
            throw new IllegalArgumentException("guide multi workflow can not be blank");
        }

        DagGraph dagGraph = DagUtil.parseAndCheck(dagJson);

        return hoconConfigBuilder.build(dagGraph, runtimeContext.getEnv());
    }

    private Map<String, Object> buildWorkflow(
            GuideMultiJobContent content,
            JobRuntimeContext runtimeContext) {

        GuideMultiJobContent.TableMatchConfig tableMatch = content.getTableMatch();
        String matchMode = resolveMatchMode(tableMatch);
        boolean kafkaFlow = isKafka(content.getSource().getDbType()) || isKafka(content.getTarget().getDbType());
        boolean patternMode = !kafkaFlow && supportsPatternMatch(content.getSource()) && isPatternMode(matchMode);

        if (isPostgreSqlCdc(content.getSource()) && MATCH_MODE_REGEX.equals(matchMode)) {
            throw new IllegalArgumentException(
                    "PostgreSQL CDC does not support regex table matching; "
                            + "please use custom, exact, or whole database matching");
        }

        /*
         * 仅 MySQL-CDC 的正则匹配 / 整库同步使用原生 table-pattern，
         * 不再提前解析所有表。PostgreSQL CDC 的整库模式会在前端加载
         * catalog 后以精确 table-names 提交。
         *
         * mode = 2: MySQL-CDC source 使用 table-pattern
         * mode = 4: MySQL-CDC source 使用 table-pattern = db\..*
         *
         * 自定义 / 精准匹配，以及 PostgreSQL CDC 整库模式，
         * 均走 tableMatchResolver 生成 table-names。
         */
        List<String> sourceTables = kafkaFlow || patternMode
                ? Collections.emptyList()
                : tableMatchResolver.resolveSourceTables(content);

        List<String> sinkTables = kafkaFlow || patternMode
                ? Collections.emptyList()
                : tableMatchResolver.resolveSinkTables(content);

        if (!kafkaFlow) {
            validateTables(content.getSource(), tableMatch, sourceTables, sinkTables, patternMode);
        }

        Map<String, Object> sourceNode = buildSourceNode(
                content.getSource(),
                tableMatch,
                sourceTables,
                patternMode,
                runtimeContext);

        Map<String, Object> sinkNode = buildSinkNode(
                content.getTarget(),
                tableMatch,
                sinkTables,
                patternMode,
                runtimeContext);

        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", EDGE_ID);
        edge.put("source", SOURCE_NODE_ID);
        edge.put("target", SINK_NODE_ID);

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("nodes", Arrays.asList(sourceNode, sinkNode));
        workflow.put("edges", Collections.singletonList(edge));

        return workflow;
    }

    private Map<String, Object> buildSourceNode(
            GuideMultiJobContent.WorkflowSourceConfig source,
            GuideMultiJobContent.TableMatchConfig tableMatch,
            List<String> sourceTables,
            boolean patternMode,
            JobRuntimeContext runtimeContext) {

        String matchMode = resolveMatchMode(tableMatch);
        String sourceMatchMode = normalizeSourceMatchMode(source, matchMode);
        String keyword = resolveKeyword(tableMatch);
        boolean multiTable = patternMode || sourceTables.size() > 1;
        String firstSourceTable = firstTable(sourceTables);

        Map<String, Object> config = new LinkedHashMap<>();

        putIfNotBlank(config, "dataSourceId", source.getDatasourceId());
        putIfNotBlank(config, "dbType", source.getDbType());
        putIfNotBlank(config, "connectorType", source.getConnectorType());
        putIfNotBlank(config, "pluginName", source.getPluginName());

        boolean kafka = isKafka(source.getDbType());
        if (!kafka) {
            config.put("readMode", "table");
        }

        /*
         * MySQL CDC 的正则匹配 / 整库同步不需要 table / table_path。
         * 否则后面的 CDC resolver 容易误判成单表或多表列表模式。
         */
        if (!kafka && !patternMode) {
            putIfNotBlank(config, "table", firstSourceTable);
            putIfNotBlank(config, "table_path", firstSourceTable);
        }

        if (!kafka) {
            config.put(KEY_MULTI_TABLE, multiTable);
        }

        putIfNotBlank(config, KEY_MATCH_MODE, sourceMatchMode);
        putIfNotBlank(config, KEY_MATCH_MODE_UNDERLINE, sourceMatchMode);

        /*
         * mode = 2 正则匹配：
         * 把前端输入的 keyword 透传给 CDC resolver。
         *
         * MultiTableCdcTableOptionResolver 里根据 matchMode=2
         * 生成 SeaTunnel CDC 原生 table-pattern。
         */
        if (MATCH_MODE_REGEX.equals(matchMode)) {
            putIfNotBlank(config, KEY_SOURCE_TABLE, keyword);
            putIfNotBlank(config, KEY_TABLE_KEYWORD, keyword);
        }

        /*
         * 除 MySQL CDC 的正则 / 整库同步外，均需要 table list。
         * PostgreSQL CDC 整库模式通过该列表生成 table-names。
         */
        if (!kafka && !patternMode) {
            putListIfNotEmpty(config, KEY_TABLE_LIST, sourceTables);
            putListIfNotEmpty(config, KEY_SOURCE_TABLE_LIST, sourceTables);
        }

        appendRuntimeConfig(config, runtimeContext);

        if (source.getFetchSize() != null) {
            config.put("fetchSize", source.getFetchSize());
        }

        if (source.getSplitSize() != null) {
            config.put("splitSize", source.getSplitSize());
        }

        if (isMySqlCdc(source)) {
            putIfNotBlank(config, "server-id", source.getServerId());
            putIfNotBlank(config, "serverIdMode", source.getServerIdMode());
        }

        if (isPostgreSqlCdc(source)) {
            putIfNotBlank(config, "slot.name", source.getSlotName());
            putIfNotBlank(config, "publicationName", source.getPublicationName());
            putIfNotBlank(config, "startup.mode", source.getStartupMode());
        }

        if (kafka) {
            putIfNotBlank(config, "topic", source.getTopic());
            putIfNotBlank(config, "pattern", source.getPattern());
            putIfNotBlank(config, "consumerGroup", source.getConsumerGroup());
            putIfNotBlank(config, "startMode", source.getStartMode());
            putIfNotNull(config, "startModeOffsets", source.getStartModeOffsets());
            putIfNotNull(config, "startModeTimestamp", source.getStartModeTimestamp());
            putIfNotNull(config, "startModeEndTimestamp", source.getStartModeEndTimestamp());
            putIfNotNull(config, "commitOnCheckpoint", source.getCommitOnCheckpoint());
            putIfNotNull(config, "pollTimeout", source.getPollTimeout());
            putIfNotBlank(config, "format", source.getFormat());
            putIfNotNull(config, "schema", source.getSchema());
            putIfNotBlank(config, "fieldDelimiter", source.getFieldDelimiter());
            putIfNotNull(config, "kafkaConfig", source.getKafkaConfig());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeType", NODE_TYPE_SOURCE);
        data.put("title", defaultIfBlank(source.getDbType(), "Source"));

        data.put("dataSourceId", source.getDatasourceId());
        data.put("dbType", source.getDbType());
        data.put("connectorType", source.getConnectorType());
        data.put("pluginName", source.getPluginName());

        appendRuntimeConfig(data, runtimeContext);

        data.putAll(config);
        data.put("config", config);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", SOURCE_NODE_ID);
        node.put("type", NODE_TYPE_SOURCE);
        node.put("data", data);

        return node;
    }

    private Map<String, Object> buildSinkNode(
            GuideMultiJobContent.WorkflowTargetConfig target,
            GuideMultiJobContent.TableMatchConfig tableMatch,
            List<String> sinkTables,
            boolean patternMode,
            JobRuntimeContext runtimeContext) {

        String matchMode = resolveMatchMode(tableMatch);
        boolean multiTable = patternMode || sinkTables.size() > 1;
        String firstSinkTable = firstTable(sinkTables);
        boolean kafka = isKafka(target.getDbType());

        Map<String, Object> config = new LinkedHashMap<>();

        putIfNotBlank(config, "dataSourceId", target.getDatasourceId());
        putIfNotBlank(config, "dbType", target.getDbType());
        putIfNotBlank(config, "connectorType", target.getConnectorType());
        putIfNotBlank(config, "pluginName", target.getPluginName());

        if (!kafka) {
            config.put(KEY_MULTI_TABLE, multiTable);
        }

        putIfNotBlank(config, KEY_MATCH_MODE, matchMode);
        putIfNotBlank(config, KEY_MATCH_MODE_UNDERLINE, matchMode);

        if (!kafka && !patternMode) {
            putListIfNotEmpty(config, KEY_TABLE_LIST, sinkTables);
            putListIfNotEmpty(config, KEY_SINK_TABLE_LIST, sinkTables);
        }

        /*
         * 多表任务由 JDBC Sink builder 生成动态表名模式。
         * 不能在这里强制写入 ${table_name}，否则会覆盖 PostgreSQL 等方言
         * 根据目标数据源 schemaName 生成的 schema.${table_name}。
         */
        if (!kafka && !multiTable) {
            putIfNotBlank(config, "table", firstSinkTable);
            putIfNotBlank(config, "table_path", firstSinkTable);
            putIfNotBlank(config, "targetTableName", firstSinkTable);
        }

        appendRuntimeConfig(config, runtimeContext);

        putIfNotBlank(config, "dataSaveMode", target.getDataSaveMode());
        putIfNotBlank(config, "schemaSaveMode", target.getSchemaSaveMode());
        putIfNotBlank(config, "fieldIde", target.getFieldIde());

        putIfNotBlank(config, "data_save_mode", target.getDataSaveMode());
        putIfNotBlank(config, "schema_save_mode", target.getSchemaSaveMode());
        putIfNotBlank(config, "field_ide", target.getFieldIde());

        if (target.getBatchSize() != null) {
            config.put("batchSize", target.getBatchSize());
            config.put("batch_size", target.getBatchSize());
        }

        if (target.getEnableUpsert() != null) {
            config.put("enableUpsert", target.getEnableUpsert());
            config.put("enable_upsert", target.getEnableUpsert());
        }

        if (kafka) {
            putIfNotBlank(config, "topic", target.getTopic());
            putIfNotBlank(config, "format", target.getFormat());
            putIfNotBlank(config, "semantics", target.getSemantics());
            putIfNotBlank(config, "transactionPrefix", target.getTransactionPrefix());
            putIfNotNull(config, "partition", target.getPartition());
            putIfNotNull(config, "partitionKeyFields", target.getPartitionKeyFields());
            putIfNotNull(config, "kafkaConfig", target.getKafkaConfig());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeType", NODE_TYPE_SINK);
        data.put("title", defaultIfBlank(target.getDbType(), "Sink"));

        data.put("dataSourceId", target.getDatasourceId());
        data.put("dbType", target.getDbType());
        data.put("connectorType", target.getConnectorType());
        data.put("pluginName", target.getPluginName());

        appendRuntimeConfig(data, runtimeContext);

        data.putAll(config);
        data.put("config", config);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", SINK_NODE_ID);
        node.put("type", NODE_TYPE_SINK);
        node.put("data", data);

        return node;
    }

    private void appendRuntimeConfig(Map<String, Object> target, JobRuntimeContext runtimeContext) {
        if (target == null || runtimeContext == null) {
            return;
        }

        JobEnvConfig env = runtimeContext.getEnv();
        if (env == null) {
            return;
        }

        if (env.getJobMode() != null) {
            target.put("jobMode", env.getJobMode().name());
            target.put("job_mode", env.getJobMode().name());
        }

        if (env.getParallelism() != null) {
            target.put("parallelism", env.getParallelism());
        }
    }

    private void validateContent(GuideMultiJobContent content) {
        if (content == null) {
            throw new IllegalArgumentException("content can not be null");
        }

        if (content.getSource() == null) {
            throw new IllegalArgumentException("content.source can not be null");
        }

        if (content.getTarget() == null) {
            throw new IllegalArgumentException("content.target can not be null");
        }

        if (content.getTableMatch() == null
                && !isKafka(content.getSource().getDbType())
                && !isKafka(content.getTarget().getDbType())) {
            throw new IllegalArgumentException("content.tableMatch can not be null");
        }

        validateSource(content.getSource());
        validateTarget(content.getTarget());
    }

    private void validateSource(GuideMultiJobContent.WorkflowSourceConfig source) {
        if (StringUtils.isBlank(source.getDatasourceId())) {
            throw new IllegalArgumentException("source.datasourceId can not be blank");
        }

        if (StringUtils.isBlank(source.getDbType())) {
            throw new IllegalArgumentException("source.dbType can not be blank");
        }

        if (StringUtils.isBlank(source.getConnectorType())) {
            throw new IllegalArgumentException("source.connectorType can not be blank");
        }

        if (StringUtils.isBlank(source.getPluginName())) {
            throw new IllegalArgumentException("source.pluginName can not be blank");
        }
    }

    private void validateTarget(GuideMultiJobContent.WorkflowTargetConfig target) {
        if (StringUtils.isBlank(target.getDatasourceId())) {
            throw new IllegalArgumentException("target.datasourceId can not be blank");
        }

        if (StringUtils.isBlank(target.getDbType())) {
            throw new IllegalArgumentException("target.dbType can not be blank");
        }

        if (StringUtils.isBlank(target.getConnectorType())) {
            throw new IllegalArgumentException("target.connectorType can not be blank");
        }

        if (StringUtils.isBlank(target.getPluginName())) {
            throw new IllegalArgumentException("target.pluginName can not be blank");
        }
    }

    private void validateTables(
            GuideMultiJobContent.WorkflowSourceConfig source,
            GuideMultiJobContent.TableMatchConfig tableMatch,
            List<String> sourceTables,
            List<String> sinkTables,
            boolean patternMode) {

        if (isKafka(source.getDbType())) {
            return;
        }

        String matchMode = resolveMatchMode(tableMatch);

        if (isPostgreSqlCdc(source)
                && MATCH_MODE_WHOLE_DATABASE.equals(matchMode)
                && CollectionUtils.isEmpty(sourceTables)) {
            throw new IllegalArgumentException(
                    "PostgreSQL CDC whole database table list is empty; "
                            + "please reload datasource table metadata");
        }

        /*
         * 正则匹配：只校验 keyword。
         * 不要求提前解析出所有表。
         */
        if (patternMode && MATCH_MODE_REGEX.equals(matchMode)) {
            if (StringUtils.isBlank(resolveKeyword(tableMatch))) {
                throw new IllegalArgumentException("table match keyword can not be blank when match mode is regex");
            }
            return;
        }

        /*
         * MySQL CDC 整库同步：直接交给 CDC table-pattern = db\..*
         * 不要求 sourceTables / sinkTables。
         */
        if (patternMode && MATCH_MODE_WHOLE_DATABASE.equals(matchMode)) {
            return;
        }

        /*
         * 自定义 / 精准匹配：仍然需要明确的表列表。
         */
        if (CollectionUtils.isEmpty(sourceTables)) {
            throw new IllegalArgumentException("source tables can not be empty");
        }

        if (CollectionUtils.isEmpty(sinkTables)) {
            throw new IllegalArgumentException("sink tables can not be empty");
        }

        if (sourceTables.size() != sinkTables.size()) {
            throw new IllegalArgumentException("source tables and sink tables size must be equal");
        }
    }

    private String resolveMatchMode(GuideMultiJobContent.TableMatchConfig tableMatch) {
        if (tableMatch == null || tableMatch.getMode() == null) {
            return MATCH_MODE_CUSTOM;
        }

        String mode = String.valueOf(tableMatch.getMode()).trim();

        if (MATCH_MODE_CUSTOM.equals(mode)) {
            return MATCH_MODE_CUSTOM;
        }

        if (MATCH_MODE_REGEX.equals(mode)) {
            return MATCH_MODE_REGEX;
        }

        if (MATCH_MODE_EXACT.equals(mode)) {
            return MATCH_MODE_EXACT;
        }

        if (MATCH_MODE_WHOLE_DATABASE.equals(mode)) {
            return MATCH_MODE_WHOLE_DATABASE;
        }

        return mode;
    }

    private String resolveKeyword(GuideMultiJobContent.TableMatchConfig tableMatch) {
        if (tableMatch == null) {
            return null;
        }

        return StringUtils.trimToNull(tableMatch.getKeyword());
    }

    private boolean isPatternMode(String matchMode) {
        return MATCH_MODE_REGEX.equals(matchMode)
                || MATCH_MODE_WHOLE_DATABASE.equals(matchMode);
    }

    private boolean supportsPatternMatch(GuideMultiJobContent.WorkflowSourceConfig source) {
        return isMySqlCdc(source);
    }

    private boolean isMySqlCdc(GuideMultiJobContent.WorkflowSourceConfig source) {
        return source != null && "MYSQL-CDC".equalsIgnoreCase(source.getPluginName());
    }

    private boolean isPostgreSqlCdc(GuideMultiJobContent.WorkflowSourceConfig source) {
        return source != null && "POSTGRESQL-CDC".equalsIgnoreCase(source.getPluginName());
    }

    private boolean isKafka(String dbType) {
        return "KAFKA".equalsIgnoreCase(StringUtils.trimToEmpty(dbType));
    }

    /**
     * SeaTunnel 2.3.13 PostgreSQL CDC only supports explicit table-names.
     * Whole-database mode is therefore expanded by the guide into the current
     * catalog table list and emitted as an exact source selection.
     */
    private String normalizeSourceMatchMode(
            GuideMultiJobContent.WorkflowSourceConfig source, String matchMode) {
        if (isPostgreSqlCdc(source) && MATCH_MODE_WHOLE_DATABASE.equals(matchMode)) {
            return MATCH_MODE_CUSTOM;
        }
        return matchMode;
    }

    private String firstTable(List<String> tables) {
        if (CollectionUtils.isEmpty(tables)) {
            return "";
        }

        for (String table : tables) {
            if (StringUtils.isNotBlank(table)) {
                return table.trim();
            }
        }

        return "";
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            map.put(key, value.trim());
        }
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private void putListIfNotEmpty(Map<String, Object> map, String key, List<String> values) {
        if (CollectionUtils.isNotEmpty(values)) {
            map.put(key, values);
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.isBlank(value) ? defaultValue : value.trim();
    }
}
