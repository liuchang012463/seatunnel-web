package org.apache.seatunnel.web.api.lake.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigValue;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.core.hocon.JobDefinitionCommandResolver;
import org.apache.seatunnel.web.core.hocon.StreamingJobDefinitionCommandResolver;
import org.apache.seatunnel.web.core.job.bridge.LakeJobBindingResolver;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.core.job.handler.script.PluginConfig;
import org.apache.seatunnel.web.core.job.handler.script.ScriptJobDefinitionParser;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideMultiJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.ScriptJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.apache.seatunnel.web.spi.bean.dto.config.ScriptJobContent;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Server-side safety gate for jobs that can write to the lake Doris data
 * source.
 *
 * <p>The gate deliberately works from the command and the current durable
 * definition.  A relation is only an additional deletion/staleness guard; it
 * is never treated as proof that a job is safe.  This is important for jobs
 * created before the lake bridge was enabled.</p>
 */
@Component
public class LakeJobGuard {

    private static final String MANAGED_SCHEMA_SAVE_MODE = "ERROR_WHEN_SCHEMA_NOT_EXIST";

    private final LakeProperties lakeProperties;
    private final DataSourceDao dataSourceDao;
    private final LakeOdsDatabaseBindingDao bindingDao;
    private final LakeOdsTableMappingDao tableMappingDao;
    private final ScriptJobDefinitionParser scriptJobDefinitionParser;
    private final LakeJobRelationDao relationDao;
    private final JobDefinitionCommandResolver batchCommandResolver;
    private final StreamingJobDefinitionCommandResolver streamingCommandResolver;
    private final LakeWarehouseService warehouseService;

    @Autowired
    public LakeJobGuard(
            LakeProperties lakeProperties,
            DataSourceDao dataSourceDao,
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            ScriptJobDefinitionParser scriptJobDefinitionParser,
            LakeJobRelationDao relationDao,
            JobDefinitionCommandResolver batchCommandResolver,
            StreamingJobDefinitionCommandResolver streamingCommandResolver,
            LakeWarehouseService warehouseService) {
        this.lakeProperties = lakeProperties;
        this.dataSourceDao = dataSourceDao;
        this.bindingDao = bindingDao;
        this.tableMappingDao = tableMappingDao;
        this.scriptJobDefinitionParser = scriptJobDefinitionParser;
        this.relationDao = relationDao;
        this.batchCommandResolver = batchCommandResolver;
        this.streamingCommandResolver = streamingCommandResolver;
        this.warehouseService = warehouseService;
    }

    /** Compatibility constructor retained for command-level clients/tests. */
    public LakeJobGuard(
            LakeProperties lakeProperties,
            DataSourceDao dataSourceDao,
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            ScriptJobDefinitionParser scriptJobDefinitionParser,
            LakeJobRelationDao relationDao,
            JobDefinitionCommandResolver batchCommandResolver,
            StreamingJobDefinitionCommandResolver streamingCommandResolver) {
        this.lakeProperties = lakeProperties;
        this.dataSourceDao = dataSourceDao;
        this.bindingDao = bindingDao;
        this.tableMappingDao = tableMappingDao;
        this.scriptJobDefinitionParser = scriptJobDefinitionParser;
        this.relationDao = relationDao;
        this.batchCommandResolver = batchCommandResolver;
        this.streamingCommandResolver = streamingCommandResolver;
        this.warehouseService = null;
    }

    /**
     * Source-compatible constructor for small clients that only need the
     * command-level save gate.  Production wiring uses the constructor above
     * so lifecycle checks always reload the durable definition.
     */
    public LakeJobGuard(
            LakeProperties lakeProperties,
            DataSourceDao dataSourceDao,
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            ScriptJobDefinitionParser scriptJobDefinitionParser) {
        this(lakeProperties, dataSourceDao, bindingDao, tableMappingDao,
                scriptJobDefinitionParser, null, null, null);
    }

    /** Validate and normalize a structured or script save command. */
    public void validateBeforeSave(JobDefinitionSaveCommand command) {
        validate(command, command == null ? null : command.getId(), ValidationPhase.SAVE);
    }

    /**
     * Validate the latest persisted definition before it is released.  The
     * resolver rebuilds the command from content, environment and schedule;
     * callers must not pass a stale browser payload here.
     */
    public void validateBeforeOnline(Long jobDefinitionId, LakeJobRuntimeType runtimeType) {
        validatePersisted(jobDefinitionId, runtimeType, ValidationPhase.ONLINE);
    }

    /** Validate the latest persisted definition immediately before execution. */
    public void validateBeforeExecute(Long jobDefinitionId, LakeJobRuntimeType runtimeType) {
        validatePersisted(jobDefinitionId, runtimeType, ValidationPhase.EXECUTE);
    }

    /** Validate an already-resolved command before an instance config is built. */
    public void validateBeforeExecute(JobDefinitionSaveCommand command) {
        try {
            restoreLegacyBinding(command, command == null ? null : command.getId());
            validate(command, command == null ? null : command.getId(), ValidationPhase.EXECUTE);
        } catch (Exception e) {
            throw invalid();
        }
    }

    /** Validate once more at the submit boundary, immediately before Engine I/O. */
    public void validateBeforeSubmit(JobInstanceVO instance, LakeJobRuntimeType runtimeType) {
        if (instance == null) {
            throw invalid();
        }
        validatePersisted(instance.getJobDefinitionId(), runtimeType, ValidationPhase.SUBMIT);
    }

    private void validatePersisted(
            Long jobDefinitionId, LakeJobRuntimeType runtimeType, ValidationPhase phase) {
        if (!isEnabled()) {
            return;
        }
        if (jobDefinitionId == null || jobDefinitionId <= 0) {
            throw invalid();
        }

        try {
            JobDefinitionSaveCommand command = resolvePersisted(jobDefinitionId, runtimeType);
            restoreLegacyBinding(command, jobDefinitionId);
            validate(command, jobDefinitionId, phase);
        } catch (Exception e) {
            // Resolver/parser exceptions may contain persisted HOCON and
            // connection parameters.  Never preserve them as a cause.
            throw invalid();
        }
    }

    private JobDefinitionSaveCommand resolvePersisted(
            Long jobDefinitionId, LakeJobRuntimeType runtimeType) {
        LakeJobRuntimeType effectiveType = runtimeType;
        if (effectiveType == null) {
            throw invalid();
        }
        JobDefinitionSaveCommand command = effectiveType == LakeJobRuntimeType.STREAMING
                ? streamingCommandResolver == null
                ? null
                : streamingCommandResolver.resolve(jobDefinitionId)
                : batchCommandResolver == null
                ? null
                : batchCommandResolver.resolve(jobDefinitionId);
        if (command == null) {
            throw invalid();
        }
        return command;
    }

    private void restoreLegacyBinding(JobDefinitionSaveCommand command, Long jobDefinitionId) {
        if (command == null || jobDefinitionId == null || relationDao == null
                || !isStructuredMode(command.getMode())
                || command.getOdsDatabaseBindingId() != null
                || LakeJobBindingResolver.resolveTargetBindingId(command) != null) {
            return;
        }

        List<LakeJobRelation> relations = relationDao.queryActiveByJobId(jobDefinitionId);
        Long bindingId = null;
        if (relations == null) {
            return;
        }
        for (LakeJobRelation relation : relations) {
            if (relation == null || relation.getRelationStatus() != LakeRelationStatus.ACTIVE
                    || relation.getOdsDatabaseBindingId() == null) {
                continue;
            }
            if (bindingId != null && !bindingId.equals(relation.getOdsDatabaseBindingId())) {
                throw invalid();
            }
            bindingId = relation.getOdsDatabaseBindingId();
        }
        if (bindingId != null) {
            command.setOdsDatabaseBindingId(bindingId);
        }
    }

    private void validate(
            JobDefinitionSaveCommand command, Long jobDefinitionId, ValidationPhase phase) {
        if (!isEnabled() || command == null || command.getMode() == null) {
            return;
        }

        if (command.getMode() == JobDefinitionMode.SCRIPT) {
            validateScript(command);
            return;
        }

        if (!isStructuredMode(command.getMode())) {
            return;
        }

        StructuredDetails details = structuredDetails(command);
        Long bindingId;
        try {
            bindingId = LakeJobBindingResolver.resolve(command);
        } catch (RuntimeException e) {
            throw invalid();
        }

        Long configuredLakeDataSourceId = configuredLakeDataSourceId();
        Long sinkLakeDataSourceId = canonicalLakeDataSourceId(details.sinkDataSourceId());
        boolean sinkIsConfiguredLake = configuredLakeDataSourceId != null
                && Objects.equals(configuredLakeDataSourceId, sinkLakeDataSourceId);

        if (bindingId == null) {
            if (sinkIsConfiguredLake) {
                throw invalid();
            }
            return;
        }

        if (configuredLakeDataSourceId == null
                || details.sinkDataSourceId() == null
                || !sinkIsConfiguredLake
                || !details.dorisTarget()) {
            throw invalid();
        }

        DataSource lakeDataSource = requiredLakeDataSource(configuredLakeDataSourceId);
        if (lakeDataSource.getDbType() == null
                || !"DORIS".equalsIgnoreCase(lakeDataSource.getDbType().getCode())) {
            throw invalid();
        }

        LakeOdsDatabaseBinding binding = requiredReadyBinding(bindingId);
        Long bindingLakeDataSourceId = canonicalLakeDataSourceId(binding.getLakeDataSourceId());
        if (!Objects.equals(configuredLakeDataSourceId, bindingLakeDataSourceId)
                || !Objects.equals(bindingLakeDataSourceId, sinkLakeDataSourceId)
                || details.sourceDataSourceId() == null
                || !Objects.equals(binding.getSourceDataSourceId(), details.sourceDataSourceId())) {
            throw invalid();
        }

        rejectDangerousSchemaMode(details.sinkConfig());
        LakeOdsTableMapping mapping = validateTableMapping(details, bindingId);
        if (mapping != null && mapping.getManagementLevel() == LakeManagementLevel.MANAGED) {
            forceManagedSchemaMode(details);
        }
        if (phase != ValidationPhase.SAVE) {
            validateActiveRelations(jobDefinitionId, details, bindingId, mapping, phase);
        }
    }

    private void validateScript(JobDefinitionSaveCommand command) {
        if (!(command instanceof ScriptJobContentCommand scriptCommand)) {
            throw invalid();
        }
        ScriptJobContent content = scriptCommand.getContent();
        if (content == null || StringUtils.isBlank(content.getHoconContent())) {
            return;
        }

        Long configuredLakeDataSourceId = configuredLakeDataSourceId();
        if (configuredLakeDataSourceId == null || scriptJobDefinitionParser == null) {
            return;
        }

        try {
            Config root = scriptJobDefinitionParser.parseAndValidate(content.getHoconContent());
            if (containsLakeDataSource(root, "source", configuredLakeDataSourceId)
                    || containsLakeDataSource(root, "sink", configuredLakeDataSourceId)) {
                throw invalid();
            }
        } catch (LakeServiceException e) {
            throw e;
        } catch (Exception e) {
            // Keep malformed/hostile script content out of exception causes.
            throw invalid();
        }
    }

    private boolean containsLakeDataSource(Config root, String section, Long lakeDataSourceId) {
        if (root == null || !root.hasPath(section)) {
            return false;
        }

        for (PluginConfig plugin : scriptJobDefinitionParser.getPluginConfigs(root, section)) {
            if (configContainsDataSource(plugin.getConfig(), lakeDataSourceId)) {
                return true;
            }
        }

        try {
            return configContainsDataSource(root.getConfig(section), lakeDataSourceId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean configContainsDataSource(Config config, Long lakeDataSourceId) {
        if (config == null) {
            return false;
        }
        for (Map.Entry<String, ConfigValue> entry : config.root().entrySet()) {
            String key = normalizeKey(entry.getKey());
            Object value = entry.getValue() == null ? null : entry.getValue().unwrapped();
            if (isDataSourceKey(key) && isLakeDataSource(parseLong(value), lakeDataSourceId)) {
                return true;
            }
            if (value instanceof Map<?, ?> map && configContainsDataSourceMap(map, lakeDataSourceId)) {
                return true;
            }
            if (value instanceof List<?> list && listContainsDataSource(list, lakeDataSourceId)) {
                return true;
            }
        }
        return false;
    }

    private boolean configContainsDataSourceMap(Map<?, ?> map, Long lakeDataSourceId) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = normalizeKey(String.valueOf(entry.getKey()));
            Object value = entry.getValue();
            if (isDataSourceKey(key) && isLakeDataSource(parseLong(value), lakeDataSourceId)) {
                return true;
            }
            if (value instanceof Map<?, ?> child && configContainsDataSourceMap(child, lakeDataSourceId)) {
                return true;
            }
            if (value instanceof List<?> list && listContainsDataSource(list, lakeDataSourceId)) {
                return true;
            }
        }
        return false;
    }

    private boolean listContainsDataSource(List<?> values, Long lakeDataSourceId) {
        for (Object value : values) {
            if (value instanceof Map<?, ?> map && configContainsDataSourceMap(map, lakeDataSourceId)) {
                return true;
            }
        }
        return false;
    }

    private LakeOdsTableMapping validateTableMapping(
            StructuredDetails details, Long bindingId) {
        if (!details.exactSingle()) {
            return null;
        }
        if (StringUtils.isBlank(details.targetTableName())) {
            throw invalid();
        }
        if (tableMappingDao == null) {
            return null;
        }
        String targetTable = details.targetTableName().trim();
        LakeOdsTableMapping mapping = safeQueryTableMapping(bindingId, targetTable);
        if (mapping != null && Boolean.TRUE.equals(mapping.getDeleted())) {
            throw invalid();
        }
        if (mapping != null) {
            return mapping;
        }

        // A deleted AUTO_CREATED/MANAGED mapping deliberately remains a
        // tombstone.  Treating an empty active lookup as a brand-new table
        // would allow CREATE_SCHEMA_WHEN_NOT_EXIST to silently recreate it.
        LakeOdsTableMapping historical = safeQueryTableMappingIncludingDeleted(bindingId, targetTable);
        if (historical != null && Boolean.TRUE.equals(historical.getDeleted())) {
            throw invalid();
        }
        return historical;
    }

    private void validateActiveRelations(
            Long jobDefinitionId,
            StructuredDetails details,
            Long bindingId,
            LakeOdsTableMapping mapping,
            ValidationPhase phase) {
        if (relationDao == null || jobDefinitionId == null || bindingId == null) {
            return;
        }

        for (LakeJobRelation relation : safeActiveRelations(jobDefinitionId)) {
            if (relation == null || relation.getRelationStatus() != LakeRelationStatus.ACTIVE) {
                continue;
            }
            if (!Objects.equals(bindingId, relation.getOdsDatabaseBindingId())) {
                throw invalid();
            }

            if (relation.getRelationScope() == LakeRelationScope.NAMESPACE) {
                if (details.exactSingle()) {
                    throw invalid();
                }
                continue;
            }

            if (relation.getRelationScope() != LakeRelationScope.TABLE
                    || !details.exactSingle()
                    || mapping == null
                    || !Objects.equals(mapping.getId(), relation.getTableMappingId())) {
                throw invalid();
            }

            LakeOdsTableMapping relatedMapping = safeQueryMappingIncludingDeleted(
                    relation.getTableMappingId());
            if (relatedMapping == null
                    || Boolean.TRUE.equals(relatedMapping.getDeleted())
                    || !Objects.equals(bindingId, relatedMapping.getOdsDatabaseBindingId())) {
                throw invalid();
            }
        }
    }

    private List<LakeJobRelation> safeActiveRelations(Long jobDefinitionId) {
        try {
            List<LakeJobRelation> relations = relationDao.queryActiveByJobId(jobDefinitionId);
            return relations == null ? Collections.emptyList() : relations;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private void rejectDangerousSchemaMode(Map<String, Object> sinkConfig) {
        if (containsDangerousSchemaValue(sinkConfig)) {
            throw invalid();
        }
    }

    private boolean containsDangerousSchemaValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = normalizeKey(String.valueOf(entry.getKey()));
                Object child = entry.getValue();
                if (isSchemaSaveKey(key) && child instanceof String string
                        && isDangerousSchemaValue(string)) {
                    return true;
                }
                if (containsDangerousSchemaValue(child)) {
                    return true;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                if (containsDangerousSchemaValue(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void forceManagedSchemaMode(StructuredDetails details) {
        if (details.multiContent() != null && details.multiContent().getTarget() != null) {
            details.multiContent().getTarget().setSchemaSaveMode(MANAGED_SCHEMA_SAVE_MODE);
        }
        setSchemaMode(details.sinkConfig());
        setSchemaMode(details.sinkData());
    }

    private void setSchemaMode(Map<String, Object> target) {
        if (target == null || target.isEmpty()) {
            return;
        }
        target.put("schemaSaveMode", MANAGED_SCHEMA_SAVE_MODE);
        target.put("schema_save_mode", MANAGED_SCHEMA_SAVE_MODE);
        for (String key : List.of("schemaSaveMode", "schema_save_mode", "schema-save-mode")) {
            if (target.containsKey(key)) {
                target.put(key, MANAGED_SCHEMA_SAVE_MODE);
            }
        }
    }

    private StructuredDetails structuredDetails(JobDefinitionSaveCommand command) {
        if (command instanceof GuideMultiJobContentCommand multiCommand) {
            GuideMultiJobContent content = multiCommand.getContent();
            if (content == null || content.getSource() == null || content.getTarget() == null) {
                throw invalid();
            }
            GuideMultiJobContent.WorkflowSourceConfig source = content.getSource();
            GuideMultiJobContent.WorkflowTargetConfig target = content.getTarget();
            Map<String, Object> sink = new java.util.LinkedHashMap<>();
            put(sink, "schemaSaveMode", target.getSchemaSaveMode());
            put(sink, "schema_save_mode", target.getSchemaSaveMode());
            put(sink, "dataSourceId", target.getDatasourceId());
            put(sink, "dbType", target.getDbType());
            put(sink, "pluginName", target.getPluginName());
            put(sink, "connectorType", target.getConnectorType());
            return new StructuredDetails(
                    parseLong(source.getDatasourceId()),
                    parseLong(target.getDatasourceId()),
                    isDorisTarget(target.getDbType(), target.getPluginName(), target.getConnectorType()),
                    false,
                    null,
                    null,
                    sink,
                    content);
        }

        if (command instanceof GuideSingleJobContentCommand singleCommand) {
            Map<String, Object> sourceNode = findNode(singleCommand.getWorkflow(), "source");
            Map<String, Object> sinkNode = findNode(singleCommand.getWorkflow(), "sink");
            Map<String, Object> sourceData = map(sourceNode.get("data"));
            Map<String, Object> sinkData = map(sinkNode.get("data"));
            Map<String, Object> sourceConfig = config(sourceData);
            Map<String, Object> sinkConfig = config(sinkData);
            Long sourceId = parseLong(firstValue(
                    sourceConfig.get("dataSourceId"), sourceConfig.get("datasourceId"),
                    sourceData.get("dataSourceId"), sourceData.get("datasourceId")));
            Long sinkId = parseLong(firstValue(
                    sinkConfig.get("dataSourceId"), sinkConfig.get("datasourceId"),
                    sinkData.get("dataSourceId"), sinkData.get("datasourceId")));
            String targetTable = firstText(
                    sinkConfig.get("targetTableName"), sinkConfig.get("target_table_name"),
                    sinkConfig.get("table"), sinkConfig.get("table_path"),
                    sinkData.get("targetTableName"), sinkData.get("target_table_name"),
                    sinkData.get("table"), sinkData.get("table_path"));
            return new StructuredDetails(
                    sourceId,
                    sinkId,
                    isDorisTarget(
                            firstText(sinkConfig.get("dbType"), sinkData.get("dbType")),
                            firstText(sinkConfig.get("pluginName"), sinkData.get("pluginName")),
                            firstText(sinkConfig.get("connectorType"), sinkData.get("connectorType"))),
                    true,
                    targetTable,
                    sinkData,
                    sinkConfig,
                    null);
        }

        throw invalid();
    }

    private Map<String, Object> findNode(Map<String, Object> workflow, String nodeType) {
        if (workflow == null || !(workflow.get("nodes") instanceof List<?> nodes)) {
            return Collections.emptyMap();
        }
        for (Object rawNode : nodes) {
            Map<String, Object> node = map(rawNode);
            Map<String, Object> data = map(node.get("data"));
            String type = firstText(data.get("nodeType"), node.get("nodeType"), node.get("type"));
            if (nodeType.equalsIgnoreCase(type)) {
                return node;
            }
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> config(Map<String, Object> data) {
        Map<String, Object> nested = map(data.get("config"));
        return nested.isEmpty() ? data : nested;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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

    private Long parseLong(Object value) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isDorisTarget(String dbType, String pluginName, String connectorType) {
        return "DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(dbType))
                && ("DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(pluginName))
                || "DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(connectorType)));
    }

    private boolean isStructuredMode(JobDefinitionMode mode) {
        return mode == JobDefinitionMode.GUIDE_SINGLE
                || mode == JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL
                || mode == JobDefinitionMode.GUIDE_MULTI;
    }

    private boolean isEnabled() {
        return true;
    }

    private Long configuredLakeDataSourceId() {
        if (warehouseService != null) {
            try {
                org.apache.seatunnel.web.spi.bean.vo.LakeWarehouseConfigVO config =
                        warehouseService.getConfig();
                return config == null ? null : config.getSystemDataSourceId();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (lakeProperties == null || lakeProperties.getDataSourceId() == null
                || lakeProperties.getDataSourceId() <= 0) {
            return null;
        }
        return lakeProperties.getDataSourceId();
    }

    /** Resolve historical lake datasource IDs through the durable alias map. */
    private Long canonicalLakeDataSourceId(Long requestedId) {
        if (requestedId == null) {
            return null;
        }
        if (warehouseService == null) {
            return requestedId;
        }
        try {
            return warehouseService.canonicalDataSourceId(requestedId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isLakeDataSource(Long requestedId, Long canonicalId) {
        return requestedId != null && canonicalId != null
                && Objects.equals(canonicalLakeDataSourceId(requestedId), canonicalId);
    }

    private DataSource requiredLakeDataSource(Long id) {
        if (dataSourceDao == null) {
            throw invalid();
        }
        try {
            DataSource dataSource = dataSourceDao.queryById(id);
            if (dataSource == null) {
                throw invalid();
            }
            return dataSource;
        } catch (LakeServiceException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private LakeOdsDatabaseBinding requiredReadyBinding(Long id) {
        if (bindingDao == null) {
            throw invalid();
        }
        try {
            LakeOdsDatabaseBinding binding = bindingDao.queryActiveById(id);
            if (binding == null
                    || Boolean.TRUE.equals(binding.getDeleted())
                    || binding.getResourceStatus() != LakeResourceStatus.READY
                    || StringUtils.isBlank(binding.getDatabaseName())) {
                throw invalid();
            }
            return binding;
        } catch (LakeServiceException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private LakeOdsTableMapping safeQueryTableMapping(Long bindingId, String targetTable) {
        try {
            return tableMappingDao.queryByBindingIdAndTargetTable(bindingId, targetTable.trim());
        } catch (Exception e) {
            throw invalid();
        }
    }

    private LakeOdsTableMapping safeQueryTableMappingIncludingDeleted(
            Long bindingId, String targetTable) {
        try {
            return tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(
                    bindingId, targetTable.trim());
        } catch (Exception e) {
            throw invalid();
        }
    }

    private LakeOdsTableMapping safeQueryMappingIncludingDeleted(Long mappingId) {
        if (mappingId == null) {
            return null;
        }
        try {
            return tableMappingDao.queryByIdIncludingDeleted(mappingId);
        } catch (Exception e) {
            throw invalid();
        }
    }

    private boolean isDataSourceKey(String key) {
        return "datasourceid".equals(key)
                || "data_source_id".equals(key)
                || "datasource_id".equals(key);
    }

    private boolean isSchemaSaveKey(String key) {
        return "schemasavemode".equals(key)
                || "schema_save_mode".equals(key)
                || "schema-save-mode".equals(key);
    }

    private String normalizeKey(String key) {
        return StringUtils.trimToEmpty(key).toLowerCase(Locale.ROOT);
    }

    private boolean isDangerousSchemaValue(String value) {
        String normalized = StringUtils.trimToEmpty(value)
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return normalized.contains("RECREATE")
                || normalized.contains("DROP_AND_CREATE")
                || normalized.contains("DROP_CREATE")
                || normalized.contains("DROP_SCHEMA");
    }

    private LakeServiceException invalid() {
        return new LakeServiceException(
                LakeErrorCode.LAKE_REQUEST_INVALID,
                "lake job safety validation failed");
    }

    private enum ValidationPhase {
        SAVE,
        ONLINE,
        EXECUTE,
        SUBMIT
    }

    private record StructuredDetails(
            Long sourceDataSourceId,
            Long sinkDataSourceId,
            boolean dorisTarget,
            boolean exactSingle,
            String targetTableName,
            Map<String, Object> sinkData,
            Map<String, Object> sinkConfig,
            GuideMultiJobContent multiContent) {
    }

}
