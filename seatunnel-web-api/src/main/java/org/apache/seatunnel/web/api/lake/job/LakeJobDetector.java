package org.apache.seatunnel.web.api.lake.job;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.job.bridge.LakeJobBindingResolver;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideMultiJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.StreamingJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the narrow structured-job shape that is allowed to create a lake
 * job relation.  Scripts, file sync jobs, non-Doris sinks, and jobs targeting
 * another data source remain ordinary jobs.
 */
@Component
public class LakeJobDetector {

    private static final String KEY_DATA = "data";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_NODE_TYPE = "nodeType";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_SINK = "sink";

    private final LakeOdsDatabaseBindingDao bindingDao;
    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeProperties lakeProperties;

    @Autowired
    public LakeJobDetector(
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeProperties lakeProperties) {
        this.bindingDao = bindingDao;
        this.tableMappingDao = tableMappingDao;
        this.lakeProperties = lakeProperties;
    }

    /** Detect using the runtime family exposed by the command. */
    public LakeJobDescriptor detect(JobDefinitionSaveCommand command) {
        return detect(command, runtimeType(command));
    }

    /**
     * Detect a structured lake job.  A null result is intentional: it means
     * that the command is an ordinary job and therefore must not acquire a
     * lake relation.
     */
    public LakeJobDescriptor detect(
            JobDefinitionSaveCommand command, LakeJobRuntimeType runtimeType) {
        if (command == null || !isStructuredMode(command.getMode())) {
            return null;
        }

        Long bindingId = LakeJobBindingResolver.resolve(command);
        if (bindingId == null || bindingDao == null || lakeProperties == null) {
            return null;
        }

        LakeOdsDatabaseBinding binding = bindingDao.queryActiveById(bindingId);
        if (!isReadyBinding(binding)) {
            return null;
        }

        Long configuredLakeDataSourceId = lakeProperties.getDataSourceId();
        if (configuredLakeDataSourceId == null
                || !configuredLakeDataSourceId.equals(binding.getLakeDataSourceId())) {
            return null;
        }

        if (command instanceof GuideMultiJobContentCommand multiCommand) {
            return detectMulti(multiCommand.getContent(), binding, runtimeType);
        }
        if (command instanceof GuideSingleJobContentCommand singleCommand) {
            return detectSingle(singleCommand.getWorkflow(), binding, runtimeType);
        }
        return null;
    }

    private LakeJobDescriptor detectMulti(
            GuideMultiJobContent content,
            LakeOdsDatabaseBinding binding,
            LakeJobRuntimeType runtimeType) {
        if (content == null || content.getSource() == null || content.getTarget() == null) {
            return null;
        }

        GuideMultiJobContent.WorkflowSourceConfig source = content.getSource();
        GuideMultiJobContent.WorkflowTargetConfig target = content.getTarget();
        Long sinkDataSourceId = parseLong(target.getDatasourceId());
        if (!isDorisTarget(target.getDbType(), target.getPluginName(), target.getConnectorType())
                || sinkDataSourceId == null
                || !sinkDataSourceId.equals(lakeProperties.getDataSourceId())
                || !sinkDataSourceId.equals(binding.getLakeDataSourceId())) {
            return null;
        }

        Map<String, Object> sourceEndpoint = new LinkedHashMap<>();
        put(sourceEndpoint, "dataSourceId", source.getDatasourceId());
        put(sourceEndpoint, "dbType", source.getDbType());
        put(sourceEndpoint, "connectorType", source.getConnectorType());
        put(sourceEndpoint, "pluginName", source.getPluginName());
        appendTableMatch(sourceEndpoint, content.getTableMatch());

        Map<String, Object> sinkEndpoint = new LinkedHashMap<>();
        put(sinkEndpoint, "dataSourceId", target.getDatasourceId());
        put(sinkEndpoint, "dbType", target.getDbType());
        put(sinkEndpoint, "connectorType", target.getConnectorType());
        put(sinkEndpoint, "pluginName", target.getPluginName());
        put(sinkEndpoint, "dataSaveMode", target.getDataSaveMode());
        put(sinkEndpoint, "schemaSaveMode", target.getSchemaSaveMode());
        put(sinkEndpoint, "fieldIde", target.getFieldIde());
        appendTableMatch(sinkEndpoint, content.getTableMatch());

        return new LakeJobDescriptor(
                binding.getId(),
                binding.getLakeDataSourceId(),
                parseLong(source.getDatasourceId()),
                sinkDataSourceId,
                LakeRelationScope.NAMESPACE,
                null,
                effectiveRuntimeType(runtimeType),
                json(sourceEndpoint),
                json(sinkEndpoint),
                trimToNull(target.getSchemaSaveMode()));
    }

    private LakeJobDescriptor detectSingle(
            Map<String, Object> workflow,
            LakeOdsDatabaseBinding binding,
            LakeJobRuntimeType runtimeType) {
        Map<String, Object> sourceNode = findNode(workflow, KEY_SOURCE);
        Map<String, Object> sinkNode = findNode(workflow, KEY_SINK);
        Map<String, Object> sourceData = map(sourceNode.get(KEY_DATA));
        Map<String, Object> sinkData = map(sinkNode.get(KEY_DATA));
        Map<String, Object> sourceConfig = config(sourceData);
        Map<String, Object> sinkConfig = config(sinkData);

        Long sinkDataSourceId = parseLong(firstValue(
                sinkConfig.get("dataSourceId"), sinkConfig.get("datasourceId"),
                sinkData.get("dataSourceId"), sinkData.get("datasourceId")));
        if (!isDorisTarget(text(firstValue(
                sinkConfig.get("dbType"), sinkData.get("dbType"))),
                text(firstValue(sinkConfig.get("pluginName"), sinkData.get("pluginName"))),
                text(firstValue(sinkConfig.get("connectorType"), sinkData.get("connectorType"))))
                || sinkDataSourceId == null
                || !sinkDataSourceId.equals(lakeProperties.getDataSourceId())
                || !sinkDataSourceId.equals(binding.getLakeDataSourceId())) {
            return null;
        }

        String targetTable = firstText(
                sinkConfig.get("targetTableName"), sinkConfig.get("target_table_name"),
                sinkConfig.get("table"), sinkConfig.get("table_path"),
                sinkData.get("targetTableName"), sinkData.get("target_table_name"),
                sinkData.get("table"), sinkData.get("table_path"));
        if (StringUtils.isBlank(targetTable) || tableMappingDao == null) {
            return null;
        }

        LakeOdsTableMapping mapping = tableMappingDao.queryByBindingIdAndTargetTable(
                binding.getId(), targetTable.trim());
        if (mapping == null
                || Boolean.TRUE.equals(mapping.getDeleted())
                || mapping.getManagementLevel() == null
                || (mapping.getManagementLevel() != LakeManagementLevel.MANAGED
                && mapping.getManagementLevel() != LakeManagementLevel.AUTO_CREATED)) {
            return null;
        }

        return new LakeJobDescriptor(
                binding.getId(),
                binding.getLakeDataSourceId(),
                parseLong(firstValue(
                        sourceConfig.get("dataSourceId"), sourceConfig.get("datasourceId"),
                        sourceData.get("dataSourceId"), sourceData.get("datasourceId"))),
                sinkDataSourceId,
                LakeRelationScope.TABLE,
                mapping.getId(),
                effectiveRuntimeType(runtimeType),
                json(endpointSnapshot(sourceData, sourceConfig, false)),
                json(endpointSnapshot(sinkData, sinkConfig, true)),
                firstText(sinkConfig.get("schemaSaveMode"), sinkConfig.get("schema_save_mode"),
                        sinkData.get("schemaSaveMode"), sinkData.get("schema_save_mode")));
    }

    private boolean isStructuredMode(JobDefinitionMode mode) {
        return mode == JobDefinitionMode.GUIDE_SINGLE
                || mode == JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL
                || mode == JobDefinitionMode.GUIDE_MULTI;
    }

    private boolean isReadyBinding(LakeOdsDatabaseBinding binding) {
        return binding != null
                && !Boolean.TRUE.equals(binding.getDeleted())
                && binding.getResourceStatus() == LakeResourceStatus.READY
                && StringUtils.isNotBlank(binding.getDatabaseName());
    }

    private boolean isDorisTarget(String dbType, String pluginName, String connectorType) {
        return "DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(dbType))
                && ("DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(pluginName))
                || "DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(connectorType)));
    }

    private LakeJobRuntimeType runtimeType(JobDefinitionSaveCommand command) {
        if (command instanceof BatchJobSaveCommand) {
            return LakeJobRuntimeType.BATCH;
        }
        if (command instanceof StreamingJobSaveCommand) {
            return LakeJobRuntimeType.STREAMING;
        }
        return null;
    }

    private LakeJobRuntimeType effectiveRuntimeType(LakeJobRuntimeType runtimeType) {
        return runtimeType == null ? LakeJobRuntimeType.BATCH : runtimeType;
    }

    private Map<String, Object> findNode(Map<String, Object> workflow, String type) {
        if (workflow == null || !(workflow.get("nodes") instanceof List<?> nodes)) {
            return Collections.emptyMap();
        }
        for (Object rawNode : nodes) {
            Map<String, Object> node = map(rawNode);
            Map<String, Object> data = map(node.get(KEY_DATA));
            if (type.equalsIgnoreCase(text(firstValue(
                    data.get(KEY_NODE_TYPE), node.get(KEY_NODE_TYPE), node.get("type"))))) {
                return node;
            }
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> config(Map<String, Object> data) {
        Map<String, Object> config = map(data.get(KEY_CONFIG));
        return config.isEmpty() ? data : config;
    }

    private Map<String, Object> endpointSnapshot(
            Map<String, Object> data, Map<String, Object> config, boolean sink) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(List.of(
                "dataSourceId", "datasourceId", "dbType", "connectorType", "pluginName",
                "table", "table_path", "tableName", "targetTableName", "target_table_name",
                "table_list", "source_table_list", "sink_table_list", "matchMode", "match_mode",
                "keyword", "schemaName", "schema_name", "database", "databaseName",
                "dataSaveMode", "data_save_mode", "schemaSaveMode", "schema_save_mode",
                "fieldIde", "field_ide"));
        for (String key : keys) {
            Object value = firstValue(config.get(key), data.get(key));
            if (value != null) {
                result.put(key, value);
            }
        }
        result.put("endpointRole", sink ? KEY_SINK : KEY_SOURCE);
        return result;
    }

    private void appendTableMatch(
            Map<String, Object> endpoint, GuideMultiJobContent.TableMatchConfig tableMatch) {
        if (tableMatch == null) {
            return;
        }
        put(endpoint, "matchMode", tableMatch.getMode());
        put(endpoint, "tables", tableMatch.getTables());
        put(endpoint, "keyword", tableMatch.getKeyword());
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value instanceof String string && StringUtils.isBlank(string)) {
            return;
        }
        if (value != null) {
            target.put(key, value);
        }
    }

    private String json(Map<String, Object> value) {
        String json = JSONUtils.toJsonString(value);
        return StringUtils.defaultIfBlank(json, "{}");
    }

    private Object firstValue(Object... values) {
        for (Object value : values) {
            if (value != null && (!(value instanceof String string) || StringUtils.isNotBlank(string))) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        Object value = firstValue(values);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private Long parseLong(Object value) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.longValue()
                    : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        return (Map<String, Object>) (Map<?, ?>) map;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
