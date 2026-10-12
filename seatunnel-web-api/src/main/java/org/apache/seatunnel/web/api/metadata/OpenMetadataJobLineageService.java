package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
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
            Map<String, Set<String>> fieldMappings = extractFieldMappings(instance.getRuntimeConfig());
            if (fieldMappings.isEmpty()) {
                log.debug("Skipping OpenMetadata lineage without FieldMapper mappings, instanceId={}",
                        instance.getId());
                return;
            }

            OpenMetadataTable source = resolveTable(
                    instance.getSourceDatasourceId(), instance.getSourceTable());
            OpenMetadataTable target = resolveTable(
                    instance.getSinkDatasourceId(), instance.getSinkTable());
            if (source == null || target == null) {
                log.info("Skipping OpenMetadata lineage because a source or target table is not registered, "
                                + "instanceId={}, sourceTable={}, targetTable={}",
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

    private Map<String, Set<String>> extractFieldMappings(String runtimeConfig) {
        if (StringUtils.isBlank(runtimeConfig)) {
            return Map.of();
        }
        Map<String, Set<String>> mappings = new TreeMap<>();
        ConfigObject root = ConfigFactory.parseString(runtimeConfig).root();
        collectFieldMappings(root, mappings);
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

    private OpenMetadataTable resolveTable(Long dataSourceId, String tableReference) {
        if (dataSourceId == null || StringUtils.isBlank(tableReference)) {
            return null;
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null || StringUtils.isBlank(binding.getOmServiceFqn())) {
            return null;
        }

        String serviceFqn = binding.getOmServiceFqn();
        String normalizedReference = normalizeTableReference(tableReference);
        if (StringUtils.isBlank(normalizedReference)) {
            return null;
        }
        String candidateFqn = normalizedReference.regionMatches(
                true, 0, serviceFqn + ".", 0, serviceFqn.length() + 1)
                ? normalizedReference
                : serviceFqn + "." + normalizedReference;

        OpenMetadataTable table = openMetadataClient.getTableByName(candidateFqn);
        return belongsToService(table, serviceFqn) ? table : null;
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
        return String.join(".", normalized);
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
        for (Map.Entry<String, Set<String>> mapping : mappings.entrySet()) {
            String toColumn = findColumnFqn(target, mapping.getKey());
            if (toColumn == null) {
                continue;
            }
            ArrayNode fromColumns = OBJECT_MAPPER.createArrayNode();
            for (String sourceField : mapping.getValue()) {
                String fromColumn = findColumnFqn(source, sourceField);
                if (fromColumn != null) {
                    fromColumns.add(fromColumn);
                }
            }
            if (!fromColumns.isEmpty()) {
                ObjectNode columnLineage = columnsLineage.addObject();
                columnLineage.set("fromColumns", fromColumns);
                columnLineage.put("toColumn", toColumn);
            }
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
