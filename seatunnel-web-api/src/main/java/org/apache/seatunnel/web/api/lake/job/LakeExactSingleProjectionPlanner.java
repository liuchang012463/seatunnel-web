package org.apache.seatunnel.web.api.lake.job;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.core.job.bridge.LakeJobBindingResolver;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.StreamingJobSaveCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the local projection for an exact single-table lake job.
 *
 * <p>This class is intentionally a planner, not a save service.  It only
 * reads the binding, table-mapping history, and Doris existence state.  The
 * returned value contains scalar snapshots rather than mutable DAO entities
 * or raw workflow configuration, so a caller can use it as the input to a
 * later transactional save without retaining secrets from the command.</p>
 */
@Component
public final class LakeExactSingleProjectionPlanner {

    private static final String KEY_DATA = "data";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_NODE_TYPE = "nodeType";
    private static final String KEY_TYPE = "type";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_SINK = "sink";

    private static final String[] DATA_SOURCE_KEYS = {
        "dataSourceId", "datasourceId", "data_source_id", "datasource_id"
    };
    private static final String[] BINDING_KEYS = {
        "odsDatabaseBindingId", "ods_database_binding_id", "ods-database-binding-id"
    };
    private static final String[] SOURCE_TABLE_KEYS = {
        "table", "tableName", "table_name", "table_path", "sourceTable", "source_table"
    };
    private static final String[] TARGET_TABLE_KEYS = {
        "targetTableName", "target_table_name", "target-table-name",
        "table", "tableName", "table_name", "table_path"
    };
    private static final String[] SCHEMA_MODE_KEYS = {
        "schemaSaveMode", "schema_save_mode", "schema-save-mode"
    };
    private static final String[] OM_ENTITY_ID_KEYS = {
        "omEntityId", "om_entity_id", "om-entity-id"
    };

    /**
     * A single-table lake save may either leave an existing schema untouched
     * or ask SeaTunnel to create a missing schema.  UPDATE/RECREATE/DROP
     * modes are deliberately not accepted by this read-only planner: they
     * would make the projection decision depend on a destructive operation.
     */
    private static final Set<String> SAFE_SCHEMA_MODES = Set.of(
            "ERROR_WHEN_SCHEMA_NOT_EXIST",
            "CREATE_SCHEMA_WHEN_NOT_EXIST");

    /** Keys that make a source endpoint a selector/query rather than one table. */
    private static final Set<String> INEXACT_SOURCE_KEYS = Set.of(
            "query", "sql", "tablelist", "tablenames", "sourcetablelist", "tables",
            "matchmode", "keyword", "pattern", "regex", "tablepattern",
            "schemapattern", "databasepattern", "schemalist", "databaselist",
            "readmode");

    private static final String CREATE_WHEN_NOT_EXIST = "CREATE_SCHEMA_WHEN_NOT_EXIST";

    private static boolean isInexactSourceKey(String key) {
        return INEXACT_SOURCE_KEYS.contains(
                StringUtils.trimToEmpty(key).toLowerCase(Locale.ROOT)
                        .replace("_", "")
                        .replace("-", ""));
    }

    private final LakeOdsDatabaseBindingDao bindingDao;
    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeDorisClientProvider dorisClientProvider;
    private final LakeProperties lakeProperties;
    private final LakeWarehouseService warehouseService;

    @Autowired
    public LakeExactSingleProjectionPlanner(
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeDorisClientProvider dorisClientProvider,
            LakeProperties lakeProperties,
            LakeWarehouseService warehouseService) {
        this.bindingDao = Objects.requireNonNull(bindingDao, "bindingDao");
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.lakeProperties = lakeProperties;
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService");
    }

    /** Compatibility constructor retained for callers that resolve the warehouse separately. */
    public LakeExactSingleProjectionPlanner(
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeDorisClientProvider dorisClientProvider,
            LakeProperties lakeProperties) {
        this.bindingDao = Objects.requireNonNull(bindingDao, "bindingDao");
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.lakeProperties = lakeProperties;
        this.warehouseService = null;
    }

    /**
     * Convenience constructor for callers that resolve the configured lake
     * data source outside this planner.  A non-null {@link LakeProperties}
     * still acts as an optional configured-data-source check.
     */
    public LakeExactSingleProjectionPlanner(
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeDorisClientProvider dorisClientProvider) {
        this(bindingDao, tableMappingDao, dorisClientProvider, null);
    }

    /** Plan an exact single-table projection without changing durable state. */
    public ProjectionPlan plan(JobDefinitionSaveCommand command) {
        if (command == null || !isSingleMode(command.getMode())) {
            return ProjectionPlan.notApplicable();
        }
        if (!(command instanceof GuideSingleJobContentCommand singleCommand)) {
            throw invalid();
        }

        SingleCommand parsed = parseSingleCommand(command, singleCommand.getWorkflow());
        if (!parsed.sink().isConfiguredDorisTarget()) {
            return ProjectionPlan.notApplicable();
        }

        Long configuredLakeId = configuredLakeDataSourceId();
        Long parsedSinkLakeId = canonicalLakeDataSourceId(parsed.sink().dataSourceId());
        if (configuredLakeId != null && !configuredLakeId.equals(parsedSinkLakeId)) {
            return ProjectionPlan.notApplicable();
        }

        validateSchemaMode(parsed.sink());
        validateExactSource(parsed.source());

        Long bindingId = resolveBindingId(command, parsed.sink());
        LakeOdsDatabaseBinding binding = readBinding(bindingId);
        validateBinding(binding, bindingId, parsed, parsedSinkLakeId);

        String databaseName;
        try {
            databaseName = DorisIdentifier.normalize(binding.getDatabaseName());
        } catch (RuntimeException exception) {
            throw invalid();
        }
        String targetTableName;
        try {
            targetTableName = DorisIdentifier.normalize(parsed.sink().tableLocator());
        } catch (RuntimeException exception) {
            throw invalid();
        }

        LakeOdsTableMapping activeMapping;
        try {
            activeMapping = tableMappingDao.queryByBindingIdAndTargetTable(bindingId, targetTableName);
        } catch (RuntimeException exception) {
            return parsed.unknownPlan(
                    binding,
                    databaseName,
                    targetTableName,
                    null,
                    "LAKE_MAPPING_UNAVAILABLE",
                    "lake table mapping state is unavailable");
        }

        // An active row is authoritative.  Looking at the history table as
        // well would make a concurrent tombstone appear to compete with the
        // row the active query already selected, and would violate the
        // active-first read contract.
        if (activeMapping != null) {
            if (Boolean.TRUE.equals(activeMapping.getDeleted())) {
                return parsed.plan(
                        Decision.REJECT,
                        binding,
                        databaseName,
                        targetTableName,
                        snapshot(activeMapping),
                        null,
                        LakeErrorCode.LAKE_REQUEST_INVALID,
                        "lake table mapping was deleted");
            }
            return reuseExistingMapping(
                    parsed, binding, databaseName, targetTableName, activeMapping);
        }

        LakeOdsTableMapping historicalMapping;
        try {
            historicalMapping = tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(
                    bindingId, targetTableName);
        } catch (RuntimeException exception) {
            return parsed.unknownPlan(
                    binding,
                    databaseName,
                    targetTableName,
                    null,
                    "LAKE_MAPPING_UNAVAILABLE",
                    "lake table mapping state is unavailable");
        }

        ExistingMapping historicalSnapshot = snapshot(historicalMapping);
        if (Boolean.TRUE.equals(historicalMapping == null ? null : historicalMapping.getDeleted())) {
            return parsed.plan(
                    Decision.REJECT,
                    binding,
                    databaseName,
                    targetTableName,
                    historicalSnapshot,
                    null,
                    LakeErrorCode.LAKE_REQUEST_INVALID,
                    "lake table mapping was deleted");
        }

        // A non-deleted row returned only by the including-deleted lookup is
        // an inconsistent DAO view.  Do not turn it into a new projection.
        if (historicalMapping != null) {
            return parsed.unknownPlan(
                    binding,
                    databaseName,
                    targetTableName,
                    snapshot(historicalMapping),
                    "LAKE_MAPPING_STATE_CONFLICT",
                    "lake table mapping state is inconsistent");
        }

        Boolean tableExists = queryDorisTable(binding.getLakeDataSourceId(), databaseName, targetTableName);
        if (tableExists == null) {
            return parsed.unknownPlan(
                    binding,
                    databaseName,
                    targetTableName,
                    null,
                    "LAKE_DORIS_UNAVAILABLE",
                    "lake Doris table state is unavailable");
        }
        if (tableExists) {
            return parsed.plan(
                    Decision.CREATE_UNMANAGED_READY,
                    binding,
                    databaseName,
                    targetTableName,
                    null,
                    tableExists,
                    null,
                    null);
        }
        if (CREATE_WHEN_NOT_EXIST.equals(parsed.sink().normalizedSchemaSaveMode())) {
            return parsed.plan(
                    Decision.CREATE_AUTO_PENDING,
                    binding,
                    databaseName,
                    targetTableName,
                    null,
                    tableExists,
                    null,
                    null);
        }
        return parsed.plan(
                Decision.REJECT,
                binding,
                databaseName,
                targetTableName,
                null,
                null,
                LakeErrorCode.LAKE_REQUEST_INVALID,
                "lake target table is missing and schema mode is not create-when-missing");
    }

    /** Alias that keeps the planner usable from projection-oriented callers. */
    public ProjectionPlan project(JobDefinitionSaveCommand command) {
        return plan(command);
    }

    private ProjectionPlan reuseExistingMapping(
            SingleCommand parsed,
            LakeOdsDatabaseBinding binding,
            String databaseName,
            String targetTableName,
            LakeOdsTableMapping mapping) {
        Decision decision;
        if (mapping.getManagementLevel() == LakeManagementLevel.MANAGED) {
            decision = Decision.REUSE_MANAGED;
        } else if (mapping.getManagementLevel() == LakeManagementLevel.AUTO_CREATED) {
            decision = Decision.REUSE_AUTO_CREATED;
        } else if (mapping.getManagementLevel() == LakeManagementLevel.UNMANAGED) {
            decision = Decision.REUSE_UNMANAGED;
        } else {
            decision = null;
        }
        if (decision == null) {
            return parsed.unknownPlan(
                    binding,
                    databaseName,
                    targetTableName,
                    snapshot(mapping),
                    "LAKE_MAPPING_STATE_INVALID",
                    "lake table mapping management state is invalid");
        }
        Boolean tableExists = queryDorisTable(binding.getLakeDataSourceId(), databaseName, targetTableName);
        if (tableExists == null) {
            return parsed.unknownPlan(
                    binding,
                    databaseName,
                    targetTableName,
                    snapshot(mapping),
                    "LAKE_DORIS_UNAVAILABLE",
                    "lake Doris table state is unavailable");
        }
        return parsed.plan(
                decision,
                binding,
                databaseName,
                targetTableName,
                snapshot(mapping),
                tableExists,
                null,
                null);
    }

    private Boolean queryDorisTable(Long lakeDataSourceId, String databaseName, String targetTableName) {
        try {
            DorisLakeClient client = dorisClientProvider.get(lakeDataSourceId);
            if (client == null) {
                return null;
            }
            return client.tableExists(databaseName, targetTableName);
        } catch (RuntimeException exception) {
            // Do not expose provider/JDBC messages: they may contain endpoint
            // or credential details.  The caller receives only UNKNOWN.
            return null;
        }
    }

    private void validateSchemaMode(EndpointValues sink) {
        if (sink == null || !SAFE_SCHEMA_MODES.contains(sink.normalizedSchemaSaveMode())) {
            throw invalid();
        }
    }

    /**
     * A source table is exact only when its endpoint names one table.  A
     * query, a table list, or a selector can produce a different projection
     * on every run, so it is intentionally rejected before any local or
     * Doris state is consulted.
     */
    private void validateExactSource(EndpointValues source) {
        if (source == null || !source.exactSource()
                || StringUtils.isBlank(source.tableLocator())
                || !isExactTableLocator(source.tableLocator())) {
            throw invalid();
        }
    }

    private boolean isExactTableLocator(String locator) {
        String value = locator.trim();
        if (value.isEmpty() || value.indexOf(',') >= 0
                || value.indexOf('*') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('%') >= 0 || value.indexOf('{') >= 0
                || value.indexOf('}') >= 0 || value.indexOf('[') >= 0
                || value.indexOf(']') >= 0) {
            return false;
        }
        return true;
    }

    private LakeOdsDatabaseBinding readBinding(Long bindingId) {
        try {
            LakeOdsDatabaseBinding binding = bindingDao.queryActiveById(bindingId);
            if (binding == null) {
                throw invalid();
            }
            return binding;
        } catch (RuntimeException exception) {
            // A DAO/provider implementation must not be able to copy a
            // persistence or connection message into the public plan.
            return null;
        }
    }

    private void validateBinding(
            LakeOdsDatabaseBinding binding,
            Long bindingId,
            SingleCommand parsed,
            Long parsedSinkLakeId) {
        Long bindingLakeId = canonicalLakeDataSourceId(
                binding == null ? null : binding.getLakeDataSourceId());
        if (binding == null
                || !Objects.equals(bindingId, binding.getId())
                || Boolean.TRUE.equals(binding.getDeleted())
                || binding.getResourceStatus() != LakeResourceStatus.READY
                || binding.getLakeDataSourceId() == null
                || binding.getLakeDataSourceId() <= 0
                || StringUtils.isBlank(binding.getDatabaseName())
                || !Objects.equals(bindingLakeId, parsedSinkLakeId)
                || !Objects.equals(binding.getSourceDataSourceId(), parsed.source().dataSourceId())) {
            throw invalid();
        }
    }

    private Long resolveBindingId(JobDefinitionSaveCommand command, EndpointValues sink) {
        Long commandBindingId;
        try {
            commandBindingId = command.getOdsDatabaseBindingId();
        } catch (RuntimeException exception) {
            throw invalid();
        }

        Long nestedBindingId = sink.bindingId();
        if (commandBindingId != null && nestedBindingId != null
                && !commandBindingId.equals(nestedBindingId)) {
            throw invalid();
        }
        Long result = commandBindingId == null ? nestedBindingId : commandBindingId;
        if (result == null || result <= 0) {
            // Invoke the shared resolver only for legacy nested payloads that
            // use the exact bridge shape; it also keeps this planner aligned
            // with the command-level binding contract.
            try {
                result = LakeJobBindingResolver.resolve(command);
            } catch (RuntimeException exception) {
                throw invalid();
            }
        }
        if (result == null || result <= 0) {
            throw invalid();
        }
        return result;
    }

    private SingleCommand parseSingleCommand(
            JobDefinitionSaveCommand command,
            Map<String, Object> workflow) {
        if (workflow == null || !(workflow.get("nodes") instanceof List<?> nodes)) {
            throw invalid();
        }

        NodeValues source = null;
        NodeValues sink = null;
        for (Object rawNode : nodes) {
            if (!(rawNode instanceof Map<?, ?> rawMap)) {
                throw invalid();
            }
            NodeValues node = NodeValues.from(rawMap);
            if (node.type() == null) {
                continue;
            }
            if (KEY_SOURCE.equalsIgnoreCase(node.type())) {
                if (source != null) {
                    throw invalid();
                }
                source = node;
            } else if (KEY_SINK.equalsIgnoreCase(node.type())) {
                if (sink != null) {
                    throw invalid();
                }
                sink = node;
            }
        }
        if (source == null || sink == null) {
            throw invalid();
        }

        Long sourceDataSourceId = readLong(source, DATA_SOURCE_KEYS, true);
        String sourceTable = readText(source, SOURCE_TABLE_KEYS, true);
        String sourceOmEntityId = readText(source, OM_ENTITY_ID_KEYS, false);
        Long sinkDataSourceId = readLong(sink, DATA_SOURCE_KEYS, true);
        String targetTable = readText(sink, TARGET_TABLE_KEYS, true);
        String dbType = readText(sink, new String[]{"dbType", "db_type", "databaseType"}, false);
        String pluginName = readText(sink, new String[]{"pluginName", "plugin_name"}, false);
        String connectorType = readText(
                sink, new String[]{"connectorType", "connector_type"}, false);
        String schemaMode = readText(sink, SCHEMA_MODE_KEYS, false);
        Long nestedBindingId = readLong(sink, BINDING_KEYS, false);

        EndpointValues sourceEndpoint = new EndpointValues(
                sourceDataSourceId, sourceTable, sourceOmEntityId, null, null,
                source.isExact());
        EndpointValues sinkEndpoint = new EndpointValues(
                sinkDataSourceId,
                targetTable,
                null,
                nestedBindingId,
                normalizeSchemaMode(schemaMode));
        sinkEndpoint = sinkEndpoint.withDoris(dbType, pluginName, connectorType);
        LakeJobRuntimeType runtimeType = runtimeType(command);
        if (runtimeType == null) {
            throw invalid();
        }
        return new SingleCommand(
                command.getMode(),
                runtimeType,
                sourceEndpoint,
                sinkEndpoint);
    }

    private LakeJobRuntimeType runtimeType(JobDefinitionSaveCommand command) {
        if (command instanceof StreamingJobSaveCommand) {
            return LakeJobRuntimeType.STREAMING;
        }
        if (command instanceof BatchJobSaveCommand) {
            return LakeJobRuntimeType.BATCH;
        }
        try {
            return switch (command.getRuntimeType()) {
                case BATCH -> LakeJobRuntimeType.BATCH;
                case STREAMING -> LakeJobRuntimeType.STREAMING;
            };
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String normalizeSchemaMode(String schemaMode) {
        if (StringUtils.isBlank(schemaMode)) {
            return null;
        }
        return schemaMode.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private boolean isSingleMode(JobDefinitionMode mode) {
        return mode == JobDefinitionMode.GUIDE_SINGLE
                || mode == JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL;
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

    /** Resolve an old lake datasource ID through the durable alias map. */
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

    private Long readLong(NodeValues node, String[] keys, boolean required) {
        String value = readText(node, keys, required);
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw invalid();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private String readText(NodeValues node, String[] keys, boolean required) {
        String result = null;
        for (Map<String, Object> values : node.values()) {
            for (String key : keys) {
                if (!values.containsKey(key)) {
                    continue;
                }
                Object raw = values.get(key);
                if (raw == null || (raw instanceof String string && StringUtils.isBlank(string))) {
                    continue;
                }
                if (!(raw instanceof String) && !(raw instanceof Number)) {
                    throw invalid();
                }
                String value = String.valueOf(raw).trim();
                if (result != null && !result.equals(value)) {
                    throw invalid();
                }
                result = value;
            }
        }
        if (required && StringUtils.isBlank(result)) {
            throw invalid();
        }
        return result;
    }

    private ExistingMapping snapshot(LakeOdsTableMapping mapping) {
        if (mapping == null) {
            return null;
        }
        return new ExistingMapping(
                mapping.getId(),
                mapping.getSourceObjectRefId(),
                mapping.getOdsDatabaseBindingId(),
                mapping.getLakeDataSourceId(),
                mapping.getDatabaseName(),
                mapping.getTargetTableName(),
                mapping.getManagementLevel(),
                mapping.getResourceStatus(),
                mapping.getGeneration(),
                mapping.getDeleted(),
                mapping.getActualTableExists());
    }

    private LakeServiceException invalid() {
        return new LakeServiceException(
                LakeErrorCode.LAKE_REQUEST_INVALID,
                "lake single-table projection request is invalid");
    }

    public enum Decision {
        NOT_APPLICABLE,
        REUSE_MANAGED,
        REUSE_AUTO_CREATED,
        REUSE_UNMANAGED,
        CREATE_AUTO_PENDING,
        CREATE_UNMANAGED_READY,
        REJECT,
        UNKNOWN

        ;

        /** Compatibility aliases for callers of the initial planner draft. */
        @Deprecated
        public static final Decision REUSE_AUTO = REUSE_AUTO_CREATED;

        @Deprecated
        public static final Decision REJECT_TOMBSTONE = REJECT;
    }

    /** Safe scalar endpoint snapshot; it never contains raw plugin config. */
    public record Endpoint(Long dataSourceId, String tableLocator, String omEntityId) {

        public String locator() {
            return tableLocator;
        }

        public String tableName() {
            return tableLocator;
        }
    }

    /** Safe scalar binding snapshot used by a projection consumer. */
    public record BindingSnapshot(
            Long id,
            Long lakeDataSourceId,
            Long sourceDataSourceId,
            String databaseName,
            LakeResourceStatus resourceStatus,
            Boolean deleted) {
    }

    /** Immutable snapshot of the active or tombstoned mapping row. */
    public record ExistingMapping(
            Long id,
            Long sourceObjectRefId,
            Long bindingId,
            Long lakeDataSourceId,
            String databaseName,
            String targetTableName,
            LakeManagementLevel managementLevel,
            LakeResourceStatus resourceStatus,
            Integer generation,
            Boolean deleted,
            Boolean actualTableExists) {
    }

    /** Result of planning.  All mutable command/entity state is copied out. */
    public record ProjectionPlan(
            Decision decision,
            JobDefinitionMode mode,
            LakeJobRuntimeType runtimeType,
            BindingSnapshot binding,
            Endpoint sourceEndpoint,
            Endpoint sinkEndpoint,
            String databaseName,
            String targetTableName,
            String schemaSaveMode,
            ExistingMapping existingMapping,
            Boolean actualTableExists,
            String failureCode,
            String failureReason) {

        public static ProjectionPlan notApplicable() {
            return new ProjectionPlan(
                    Decision.NOT_APPLICABLE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public Long bindingId() {
            return binding == null ? null : binding.id();
        }

        public Long sourceDataSourceId() {
            return sourceEndpoint == null ? null : sourceEndpoint.dataSourceId();
        }

        public Long sinkDataSourceId() {
            return sinkEndpoint == null ? null : sinkEndpoint.dataSourceId();
        }

        public String database() {
            return databaseName;
        }

        public String targetTable() {
            return targetTableName;
        }

        public boolean isUnknown() {
            return decision == Decision.UNKNOWN;
        }

        /** Alias for clients that call the first field a classification. */
        public Decision classification() {
            return decision;
        }
    }

    private record SingleCommand(
            JobDefinitionMode mode,
            LakeJobRuntimeType runtimeType,
            EndpointValues source,
            EndpointValues sink) {

        private ProjectionPlan plan(
                Decision decision,
                LakeOdsDatabaseBinding binding,
                String databaseName,
                String targetTableName,
                ExistingMapping mapping,
                Boolean tableExists,
                String failureCode,
                String failureReason) {
            return new ProjectionPlan(
                    decision,
                    mode,
                    runtimeType,
                    new BindingSnapshot(
                            binding.getId(),
                            binding.getLakeDataSourceId(),
                            binding.getSourceDataSourceId(),
                            databaseName,
                            binding.getResourceStatus(),
                            binding.getDeleted()),
                    source.toEndpoint(),
                    sink.toEndpoint(targetTableName),
                    databaseName,
                    targetTableName,
                    sink.normalizedSchemaSaveMode(),
                    mapping,
                    tableExists,
                    failureCode,
                    failureReason);
        }

        private ProjectionPlan unknownPlan(
                LakeOdsDatabaseBinding binding,
                String databaseName,
                String targetTableName,
                ExistingMapping mapping,
                String failureCode,
                String failureReason) {
            return plan(
                    Decision.UNKNOWN,
                    binding,
                    databaseName,
                    targetTableName,
                    mapping,
                    null,
                    failureCode,
                    failureReason);
        }
    }

    private record EndpointValues(
            Long dataSourceId,
            String tableLocator,
            String omEntityId,
            Long bindingId,
            String normalizedSchemaSaveMode,
            String dbType,
            String pluginName,
            String connectorType,
            boolean exactSource) {

        private EndpointValues(
                Long dataSourceId,
                String tableLocator,
                String omEntityId,
                Long bindingId,
                String normalizedSchemaSaveMode) {
            this(dataSourceId, tableLocator, omEntityId, bindingId,
                    normalizedSchemaSaveMode, null, null, null, true);
        }

        private EndpointValues(
                Long dataSourceId,
                String tableLocator,
                String omEntityId,
                Long bindingId,
                String normalizedSchemaSaveMode,
                boolean exactSource) {
            this(dataSourceId, tableLocator, omEntityId, bindingId,
                    normalizedSchemaSaveMode, null, null, null, exactSource);
        }

        private EndpointValues withDoris(String dbType, String pluginName, String connectorType) {
            return new EndpointValues(
                    dataSourceId,
                    tableLocator,
                    omEntityId,
                    bindingId,
                    normalizedSchemaSaveMode,
                    dbType,
                    pluginName,
                    connectorType,
                    exactSource);
        }

        private boolean isConfiguredDorisTarget() {
            return "DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(dbType))
                    && ("DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(pluginName))
                    || "DORIS".equalsIgnoreCase(StringUtils.trimToEmpty(connectorType)));
        }

        private Endpoint toEndpoint() {
            return new Endpoint(dataSourceId, tableLocator, omEntityId);
        }

        private Endpoint toEndpoint(String targetTableName) {
            return new Endpoint(dataSourceId, targetTableName, omEntityId);
        }
    }

    private static final class NodeValues {

        private final List<Map<String, Object>> values;
        private final String type;

        private NodeValues(List<Map<String, Object>> values, String type) {
            this.values = values;
            this.type = type;
        }

        private static NodeValues from(Map<?, ?> rawNode) {
            Map<String, Object> node = asStringObjectMap(rawNode);
            Map<String, Object> data = asStringObjectMap(node.get(KEY_DATA));
            Map<String, Object> config = asStringObjectMap(data.get(KEY_CONFIG));
            List<Map<String, Object>> values = new ArrayList<>();
            if (!config.isEmpty()) {
                values.add(config);
            }
            if (!data.isEmpty()) {
                values.add(data);
            }
            values.add(node);

            String type = null;
            for (Map<String, Object> value : List.of(data, node)) {
                for (String key : List.of(KEY_NODE_TYPE, KEY_TYPE)) {
                    if (!value.containsKey(key) || value.get(key) == null) {
                        continue;
                    }
                    Object rawType = value.get(key);
                    if (!(rawType instanceof String)) {
                        throw invalidStatic();
                    }
                    String candidate = StringUtils.trimToNull((String) rawType);
                    if (candidate == null) {
                        continue;
                    }
                    if (type != null && !type.equalsIgnoreCase(candidate)) {
                        throw invalidStatic();
                    }
                    type = candidate;
                }
            }
            return new NodeValues(values, type);
        }

        private List<Map<String, Object>> values() {
            return Collections.unmodifiableList(values);
        }

        private String type() {
            return type;
        }

        private boolean isExact() {
            for (Map<String, Object> value : values) {
                for (Map.Entry<String, Object> entry : value.entrySet()) {
                    if (!isInexactSourceKey(entry.getKey())) {
                        continue;
                    }
                    Object raw = entry.getValue();
                    if (raw == null || (raw instanceof String string
                            && StringUtils.isBlank(string))) {
                        continue;
                    }
                    if ("readmode".equals(normalizeSourceKey(entry.getKey()))
                            && raw instanceof String string
                            && "table".equalsIgnoreCase(string.trim())) {
                        continue;
                    }
                    // A selector/query is non-exact even when the value is
                    // a one-item list: accepting it would silently change
                    // semantics when the command is edited later.
                    return false;
                }
            }
            return true;
        }

        private static String normalizeSourceKey(String key) {
            return StringUtils.trimToEmpty(key).toLowerCase(Locale.ROOT)
                    .replace("_", "")
                    .replace("-", "");
        }

        private static Map<String, Object> asStringObjectMap(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }

        private static LakeServiceException invalidStatic() {
            return new LakeServiceException(
                    LakeErrorCode.LAKE_REQUEST_INVALID,
                    "lake single-table projection request is invalid");
        }
    }
}
