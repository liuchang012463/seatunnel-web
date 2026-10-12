package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Writes the field mappings of an accepted SeaTunnel job submission to OpenMetadata. */
@Service
@Slf4j
public class OpenMetadataJobLineageService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SIDE_SOURCE = "source";
    private static final String SIDE_SINK = "sink";

    private static final String OPTION_DATABASE = "database";
    private static final String OPTION_TABLE = "table";
    private static final String OPTION_TABLE_PATH = "table_path";
    private static final String OPTION_TABLE_NAMES = "table-names";
    private static final String OPTION_TABLE_NAMES_LEGACY = "table_names";
    private static final String OPTION_URL = "url";

    /**
     * OpenMetadata identifies a table by {@code service.database.schema.table}, while a job
     * definition only stores the local table reference typed in the wizard. The database level
     * cannot be derived from that reference alone, so the lookup walks a bounded list of
     * candidates built from the connector options and finally from the databases that
     * OpenMetadata reports for the bound service.
     */
    private static final int MAX_TABLE_PROBES = 12;
    private static final int MAX_SERVICE_DATABASES = 3;

    private final OpenMetadataClient openMetadataClient;
    private final MetadataBindingDao metadataBindingDao;
    private final JobInstanceDao jobInstanceDao;
    private final StreamingJobInstanceDao streamingJobInstanceDao;

    public OpenMetadataJobLineageService(
            OpenMetadataClient openMetadataClient,
            MetadataBindingDao metadataBindingDao,
            JobInstanceDao jobInstanceDao,
            StreamingJobInstanceDao streamingJobInstanceDao) {
        this.openMetadataClient = openMetadataClient;
        this.metadataBindingDao = metadataBindingDao;
        this.jobInstanceDao = jobInstanceDao;
        this.streamingJobInstanceDao = streamingJobInstanceDao;
    }

    public void recordBatchSubmission(JobInstanceVO submittedInstance, String engineJobId) {
        recordSubmission(submittedInstance, engineJobId, false);
    }

    public void recordStreamingSubmission(JobInstanceVO submittedInstance, String engineJobId) {
        recordSubmission(submittedInstance, engineJobId, true);
    }

    private void recordSubmission(JobInstanceVO submittedInstance, String engineJobId, boolean streaming) {
        if (submittedInstance == null
                || submittedInstance.getId() == null
                || StringUtils.isBlank(engineJobId)) {
            return;
        }

        try {
            JobInstanceVO instance = loadInstanceDetails(submittedInstance, streaming);
            Config runtimeConfig = parseRuntimeConfig(instance.getRuntimeConfig());
            Map<String, Set<String>> fieldMappings = extractFieldMappings(runtimeConfig);
            if (fieldMappings.isEmpty()) {
                log.debug("Skipping OpenMetadata lineage without FieldMapper mappings, instanceId={}",
                        instance.getId());
                return;
            }

            Map<String, List<String>> serviceDatabases = new HashMap<>();
            OpenMetadataTable source = resolveTable(
                    instance.getSourceDatasourceId(),
                    instance.getSourceTable(),
                    connectorOptions(runtimeConfig, SIDE_SOURCE),
                    serviceDatabases);
            OpenMetadataTable target = resolveTable(
                    instance.getSinkDatasourceId(),
                    instance.getSinkTable(),
                    connectorOptions(runtimeConfig, SIDE_SINK),
                    serviceDatabases);
            if (source == null || target == null) {
                log.warn("Skipping OpenMetadata lineage because a source or target table could not be "
                                + "resolved to a registered table, instanceId={}, sourceTable={}, targetTable={}",
                        instance.getId(), instance.getSourceTable(), instance.getSinkTable());
                return;
            }

            JsonNode request = buildLineageRequest(
                    source, target, fieldMappings, instance, engineJobId, streaming);
            if (request == null) {
                log.info("Skipping OpenMetadata lineage because none of the mapped columns exist in metadata, "
                        + "instanceId={}", instance.getId());
                return;
            }

            openMetadataClient.addLineage(request);
            log.info("Wrote OpenMetadata field lineage for submitted SeaTunnel job, "
                            + "instanceId={}, definitionId={}, engineJobId={}, sourceTable={}, targetTable={}",
                    instance.getId(), instance.getJobDefinitionId(), engineJobId,
                    source.getFullyQualifiedName(), target.getFullyQualifiedName());
        } catch (Exception error) {
            // The Engine has already accepted the job. OpenMetadata failures must not
            // change the instance's submission state.
            log.warn("Could not write OpenMetadata lineage for accepted SeaTunnel job, instanceId={}",
                    submittedInstance.getId(), error);
        }
    }

    private static Config parseRuntimeConfig(String runtimeConfig) {
        return StringUtils.isBlank(runtimeConfig) ? null : ConfigFactory.parseString(runtimeConfig);
    }

    private JobInstanceVO loadInstanceDetails(JobInstanceVO submittedInstance, boolean streaming) {
        JobInstanceVO details = streaming
                ? streamingJobInstanceDao.selectDetailById(submittedInstance.getId())
                : jobInstanceDao.selectDetailById(submittedInstance.getId());
        if (details == null) {
            details = submittedInstance;
        } else {
            copyMissingValues(submittedInstance, details);
        }
        // This is the immutable configuration used to submit this instance. Do not
        // derive field mappings from a subsequently edited job definition.
        details.setRuntimeConfig(submittedInstance.getRuntimeConfig());
        return details;
    }

    private static void copyMissingValues(JobInstanceVO submitted, JobInstanceVO details) {
        if (details.getId() == null) {
            details.setId(submitted.getId());
        }
        if (details.getJobDefinitionId() == null) {
            details.setJobDefinitionId(submitted.getJobDefinitionId());
        }
        if (details.getJobName() == null) {
            details.setJobName(submitted.getJobName());
        }
        if (details.getJobType() == null) {
            details.setJobType(submitted.getJobType());
        }
        if (details.getDefinitionMode() == null) {
            details.setDefinitionMode(submitted.getDefinitionMode());
        }
        if (details.getTaskType() == null) {
            details.setTaskType(submitted.getTaskType());
        }
        if (details.getJobVersion() == null) {
            details.setJobVersion(submitted.getJobVersion());
        }
        if (details.getRunMode() == null) {
            details.setRunMode(submitted.getRunMode());
        }
        if (details.getSourceDatasourceId() == null) {
            details.setSourceDatasourceId(submitted.getSourceDatasourceId());
        }
        if (details.getSinkDatasourceId() == null) {
            details.setSinkDatasourceId(submitted.getSinkDatasourceId());
        }
        if (details.getSourceTable() == null) {
            details.setSourceTable(submitted.getSourceTable());
        }
        if (details.getSinkTable() == null) {
            details.setSinkTable(submitted.getSinkTable());
        }
    }

    private Map<String, Set<String>> extractFieldMappings(Config runtimeConfig) {
        if (runtimeConfig == null) {
            return Map.of();
        }
        Map<String, Set<String>> mappings = new TreeMap<>();
        collectFieldMappings(runtimeConfig.root(), mappings);
        return mappings;
    }

    private static void collectFieldMappings(ConfigValue value, Map<String, Set<String>> mappings) {
        if (value instanceof ConfigObject object) {
            ConfigValue mapperValue = object.get("field_mapper");
            if (mapperValue instanceof ConfigObject mapper) {
                for (Map.Entry<String, ConfigValue> entry : mapper.entrySet()) {
                    if (entry.getValue().valueType() != ConfigValueType.STRING) {
                        continue;
                    }
                    String sourceField = normalizeIdentifier(entry.getKey());
                    String targetField = normalizeIdentifier(entry.getValue().unwrapped().toString());
                    if (StringUtils.isAnyBlank(sourceField, targetField)) {
                        continue;
                    }
                    mappings.computeIfAbsent(targetField, ignored -> new TreeSet<>()).add(sourceField);
                }
            }
            object.values().forEach(child -> collectFieldMappings(child, mappings));
        } else if (value instanceof ConfigList list) {
            list.forEach(child -> collectFieldMappings(child, mappings));
        }
    }

    /**
     * Reads the table identity a connector actually submitted: the wizard stores a local
     * reference (a bare table name, or {@code schema.table}), while the runtime config carries
     * the connector's own {@code database} and {@code table}/{@code table_path}.
     */
    private static Map<String, String> connectorOptions(Config runtimeConfig, String side) {
        if (runtimeConfig == null || !runtimeConfig.hasPath(side)) {
            return Map.of();
        }
        Config block = runtimeConfig.getConfig(side);
        Map<String, String> firstOptions = null;
        for (Map.Entry<String, ConfigValue> entry : block.root().entrySet()) {
            if (!(entry.getValue() instanceof ConfigObject)) {
                continue;
            }
            Config connector = block.getConfig(entry.getKey());
            Map<String, String> options = new LinkedHashMap<>();
            putStringOption(options, connector, OPTION_DATABASE);
            putStringOption(options, connector, OPTION_TABLE);
            putStringOption(options, connector, OPTION_TABLE_PATH);
            putStringOption(options, connector, OPTION_URL);
            putListOption(options, connector, OPTION_TABLE_NAMES);
            putListOption(options, connector, OPTION_TABLE_NAMES_LEGACY);
            if (firstOptions == null) {
                firstOptions = options;
            }
            if (options.containsKey(OPTION_TABLE) || options.containsKey(OPTION_TABLE_PATH)
                    || options.containsKey(OPTION_TABLE_NAMES)) {
                return options;
            }
        }
        return firstOptions == null ? Map.of() : firstOptions;
    }

    private static void putStringOption(Map<String, String> options, Config connector, String key) {
        if (!connector.hasPath(key)) {
            return;
        }
        try {
            String value = StringUtils.trimToNull(connector.getString(key));
            if (value != null) {
                options.put(key, value);
            }
        } catch (RuntimeException ignored) {
            // A non-string value is not a table identity.
        }
    }

    private static void putListOption(Map<String, String> options, Config connector, String key) {
        if (!connector.hasPath(key)) {
            return;
        }
        try {
            for (String value : connector.getStringList(key)) {
                String trimmed = StringUtils.trimToNull(value);
                if (trimmed != null) {
                    options.put(key, trimmed);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // A non-list value is not a table identity list.
        }
    }

    /** Reads the database from a JDBC url, for connectors that only expose the url. */
    private static String databaseFromUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        String trimmed = url.trim();
        int query = trimmed.indexOf('?');
        String base = query < 0 ? trimmed : trimmed.substring(0, query);
        int slash = base.lastIndexOf('/');
        if (slash < 0 || slash == base.length() - 1) {
            return null;
        }
        return StringUtils.trimToNull(base.substring(slash + 1));
    }

    private OpenMetadataTable resolveTable(Long dataSourceId,
                                           String definitionTable,
                                           Map<String, String> options,
                                           Map<String, List<String>> serviceDatabases) {
        if (dataSourceId == null || StringUtils.isBlank(definitionTable)) {
            return null;
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null) {
            return null;
        }
        String serviceFqn = StringUtils.defaultIfBlank(
                binding.getOmServiceFqn(), MetadataStableName.serviceFqn(dataSourceId));
        if (StringUtils.isBlank(serviceFqn)) {
            return null;
        }

        List<String> candidates = candidateFqns(serviceFqn, definitionTable, options);
        List<String> tried = new ArrayList<>();
        int probes = 0;
        for (String candidate : candidates) {
            if (++probes > MAX_TABLE_PROBES) {
                break;
            }
            tried.add(candidate);
            OpenMetadataTable table = lookupTable(serviceFqn, candidate);
            if (table != null) {
                log.debug("Resolved OpenMetadata table {} for dataSourceId={}",
                        table.getFullyQualifiedName(), dataSourceId);
                return table;
            }
        }
        for (String databaseFqn : serviceDatabases(serviceFqn, options, serviceDatabases)) {
            for (String suffix : databaseSuffixes(definitionTable, options, databaseFqn)) {
                if (++probes > MAX_TABLE_PROBES) {
                    break;
                }
                String candidate = databaseFqn + "." + suffix;
                tried.add(candidate);
                OpenMetadataTable table = lookupTable(serviceFqn, candidate);
                if (table != null) {
                    log.debug("Resolved OpenMetadata table {} for dataSourceId={}",
                            table.getFullyQualifiedName(), dataSourceId);
                    return table;
                }
            }
        }
        log.warn("No OpenMetadata table matched dataSourceId={}, table={}, tried={}",
                dataSourceId, definitionTable, tried);
        return null;
    }

    private OpenMetadataTable lookupTable(String serviceFqn, String candidateFqn) {
        OpenMetadataTable table = openMetadataClient.getTableByName(candidateFqn);
        return belongsToService(table, serviceFqn) ? table : null;
    }

    private static List<String> candidateFqns(String serviceFqn,
                                              String definitionTable,
                                              Map<String, String> options) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String tableReference = firstNonBlank(
                options.get(OPTION_TABLE_PATH),
                options.get(OPTION_TABLE),
                options.get(OPTION_TABLE_NAMES),
                definitionTable);
        String database = firstNonBlank(
                options.get(OPTION_DATABASE), databaseFromUrl(options.get(OPTION_URL)));

        // The connector reference is already a full path for PostgreSQL-style sources
        // (database.schema.table) and for Oracle-style sinks (schema.table).
        addCandidate(candidates, serviceFqn, tableReference);
        addCandidate(candidates, serviceFqn, definitionTable);
        addCandidate(candidates, serviceFqn, lastTwoParts(tableReference));
        // MySQL-style connectors repeat the schema below the database level.
        addCandidate(candidates, serviceFqn, join(database, tableReference));
        return new ArrayList<>(candidates);
    }

    /** Suffixes to try below one OpenMetadata database of the bound service. */
    private static List<String> databaseSuffixes(String definitionTable,
                                                 Map<String, String> options,
                                                 String databaseFqn) {
        LinkedHashSet<String> suffixes = new LinkedHashSet<>();
        String tableReference = firstNonBlank(
                options.get(OPTION_TABLE_PATH),
                options.get(OPTION_TABLE),
                options.get(OPTION_TABLE_NAMES),
                definitionTable);
        suffixes.add(normalizeTableReference(tableReference));
        suffixes.add(lastTwoParts(tableReference));
        // MySQL models the connected database as both the database and the schema.
        suffixes.add(join(lastPart(databaseFqn), lastPart(tableReference)));
        suffixes.add(normalizeTableReference(definitionTable));
        suffixes.removeIf(StringUtils::isBlank);
        return new ArrayList<>(suffixes);
    }

    private static String join(String prefix, String suffix) {
        return StringUtils.isAnyBlank(prefix, suffix) ? null : prefix + "." + suffix;
    }

    private static void addCandidate(Set<String> candidates, String serviceFqn, String reference) {
        String normalized = normalizeTableReference(reference);
        if (StringUtils.isBlank(normalized)) {
            return;
        }
        if (normalized.regionMatches(true, 0, serviceFqn + ".", 0, serviceFqn.length() + 1)) {
            candidates.add(normalized);
            return;
        }
        candidates.add(serviceFqn + "." + normalized);
    }

    private List<String> serviceDatabases(String serviceFqn,
                                          Map<String, String> options,
                                          Map<String, List<String>> cache) {
        List<String> cached = cache.get(serviceFqn);
        if (cached != null) {
            return cached;
        }
        List<String> databases = new ArrayList<>();
        try {
            for (OpenMetadataDatabase database
                    : openMetadataClient.listDatabases(serviceFqn, MAX_SERVICE_DATABASES)) {
                if (database != null && StringUtils.isNotBlank(database.fullyQualifiedName())) {
                    databases.add(database.fullyQualifiedName());
                }
            }
        } catch (RuntimeException error) {
            log.debug("Could not list OpenMetadata databases for service {}", serviceFqn, error);
        }
        String hint = firstNonBlank(
                options.get(OPTION_DATABASE), databaseFromUrl(options.get(OPTION_URL)));
        if (hint != null) {
            // The database whose name matches the connector is the most likely one.
            databases.sort(Comparator.comparingInt(
                    database -> databaseMatchesHint(database, hint) ? 0 : 1));
        }
        cache.put(serviceFqn, databases);
        return databases;
    }

    private static boolean databaseMatchesHint(String databaseFqn, String hint) {
        return hint.equalsIgnoreCase(lastPart(databaseFqn));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = StringUtils.trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static boolean belongsToService(OpenMetadataTable table, String serviceFqn) {
        if (table == null || StringUtils.isBlank(table.getFullyQualifiedName())) {
            return false;
        }
        if (StringUtils.isNotBlank(table.getServiceFullyQualifiedName())) {
            return serviceFqn.equalsIgnoreCase(table.getServiceFullyQualifiedName());
        }
        return table.getFullyQualifiedName().regionMatches(
                true, 0, serviceFqn + ".", 0, serviceFqn.length() + 1);
    }

    private static String normalizeTableReference(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String[] parts = value.trim().split("\\.");
        List<String> normalized = new ArrayList<>(parts.length);
        for (String part : parts) {
            String identifier = normalizeIdentifier(part);
            if (StringUtils.isNotBlank(identifier)) {
                normalized.add(identifier);
            }
        }
        return normalized.isEmpty() ? null : String.join(".", normalized);
    }

    private static String lastPart(String value) {
        String normalized = normalizeTableReference(value);
        if (normalized == null) {
            return null;
        }
        int separator = normalized.lastIndexOf('.');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    /** {@code db.schema.table} becomes {@code schema.table}; shorter references stay unchanged. */
    private static String lastTwoParts(String value) {
        String normalized = normalizeTableReference(value);
        if (normalized == null) {
            return null;
        }
        String[] parts = normalized.split("\\.");
        return parts.length <= 2
                ? normalized
                : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static String normalizeIdentifier(String value) {
        return StringUtils.trimToNull(value) == null
                ? null
                : value.trim().replace("`", "").replace("\"", "").replace("'", "");
    }

    private JsonNode buildLineageRequest(
            OpenMetadataTable source,
            OpenMetadataTable target,
            Map<String, Set<String>> mappings,
            JobInstanceVO instance,
            String engineJobId,
            boolean streaming) throws Exception {
        ArrayNode columnsLineage = OBJECT_MAPPER.createArrayNode();
        List<String> droppedMappings = new ArrayList<>();
        for (Map.Entry<String, Set<String>> mapping : mappings.entrySet()) {
            String toColumn = findColumnFqn(target, mapping.getKey());
            if (toColumn == null) {
                droppedMappings.add("-> " + mapping.getKey() + " (target column missing)");
                continue;
            }
            ArrayNode fromColumns = OBJECT_MAPPER.createArrayNode();
            for (String sourceField : mapping.getValue()) {
                String fromColumn = findColumnFqn(source, sourceField);
                if (fromColumn != null) {
                    fromColumns.add(fromColumn);
                } else {
                    droppedMappings.add(sourceField + " -> " + mapping.getKey() + " (source column missing)");
                }
            }
            if (!fromColumns.isEmpty()) {
                ObjectNode columnLineage = columnsLineage.addObject();
                columnLineage.set("fromColumns", fromColumns);
                columnLineage.put("toColumn", toColumn);
            }
        }
        if (!droppedMappings.isEmpty()) {
            // Field mappings that name no physical column cannot be written. Surface them so a
            // partial edge is not mistaken for the complete mapping of the job.
            log.warn("OpenMetadata field lineage skips unmapped columns, instanceId={}, dropped={}",
                    instance.getId(), droppedMappings);
        }
        if (columnsLineage.isEmpty()) {
            return null;
        }

        ObjectNode request = OBJECT_MAPPER.createObjectNode();
        ObjectNode edge = request.putObject("edge");
        edge.putObject("fromEntity")
                .put("id", source.getId())
                .put("type", "table");
        edge.putObject("toEntity")
                .put("id", target.getId())
                .put("type", "table");
        ObjectNode details = edge.putObject("lineageDetails");
        details.put("description", buildDescription(instance, engineJobId, streaming));
        details.set("columnsLineage", columnsLineage);
        return request;
    }

    private static String findColumnFqn(OpenMetadataTable table, String fieldName) {
        if (table.getColumns() == null) {
            return null;
        }
        String normalizedField = normalizeIdentifier(fieldName);
        for (OpenMetadataColumn column : table.getColumns()) {
            if (column != null
                    && StringUtils.equalsIgnoreCase(normalizedField, normalizeIdentifier(column.getName()))) {
                return table.getFullyQualifiedName() + "." + column.getName();
            }
        }
        return null;
    }

    private static String buildDescription(JobInstanceVO instance, String engineJobId, boolean streaming)
            throws Exception {
        ObjectNode description = OBJECT_MAPPER.createObjectNode();
        description.put("schemaVersion", 1);
        description.put("producer", "seatunnel-web");
        ObjectNode job = description.putObject("job");
        putIfPresent(job, "kind", streaming ? "STREAMING" : "BATCH");
        putIfPresent(job, "definitionId", stringValue(instance.getJobDefinitionId()));
        putIfPresent(job, "name", instance.getJobName());
        putIfPresent(job, "definitionMode", instance.getDefinitionMode());
        putIfPresent(job, "taskType", stringValue(instance.getTaskType()));
        if (instance.getJobVersion() != null) {
            job.put("version", instance.getJobVersion());
        }
        ObjectNode run = description.putObject("run");
        putIfPresent(run, "instanceId", stringValue(instance.getId()));
        putIfPresent(run, "engineJobId", engineJobId);
        putIfPresent(run, "runMode", instance.getRunMode());
        run.put("submittedAt", Instant.now().toString());
        return OBJECT_MAPPER.writeValueAsString(description);
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (StringUtils.isNotBlank(value)) {
            node.put(field, value);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
