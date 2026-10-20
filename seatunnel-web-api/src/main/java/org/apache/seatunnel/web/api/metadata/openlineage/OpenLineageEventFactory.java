package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.common.utils.MetadataStableName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Renders OpenLineage {@code RunEvent} JSON from a {@link OpenLineageSnapshot}.
 *
 * <p>
 * The output follows the OpenLineage v1 specification only: the run/job/dataset skeleton, the
 * standard {@code datasource} and {@code columnLineage} facets, and two custom facets
 * ({@code seatunnel} on the run, {@code seatunnelDatasource} on every dataset) carrying the
 * engine-native identities. Nothing here shares structure with the OpenMetadata lineage request
 * the OM channel builds.
 * </p>
 */
public final class OpenLineageEventFactory {

    /** Life-cycle event types this bridge emits, all valid OpenLineage eventType values. */
    public enum EventType {
        START,
        RUNNING,
        COMPLETE,
        FAIL,
        ABORT
    }

    public static final String PRODUCER = "https://github.com/weifuwan/seatunnel-web/lineage-bridge";

    static final String RUN_EVENT_SCHEMA_URL =
            "https://openlineage.io/spec/1-0-5/OpenLineage.json#/definitions/RunEvent";
    static final String COLUMN_LINEAGE_FACET_SCHEMA_URL =
            "https://openlineage.io/spec/facets/1-1-1/ColumnLineageDatasetFacet.json";
    static final String DATASOURCE_FACET_SCHEMA_URL =
            "https://openlineage.io/spec/facets/1-0-0/DatasourceDatasetFacet.json";
    /** Custom facets are not part of the official spec namespace, so these URLs are project-local. */
    static final String SEATUNNEL_RUN_FACET_SCHEMA_URL =
            "https://github.com/weifuwan/seatunnel-web/spec/facets/1-0-0/SeatunnelRunFacet.json";
    static final String SEATUNNEL_DATASOURCE_FACET_SCHEMA_URL =
            "https://github.com/weifuwan/seatunnel-web/spec/facets/1-0-0/SeatunnelDatasourceFacet.json";

    /** Job namespace for every event the web publishes from this module. */
    private static final String JOB_NAMESPACE = "seatunnel-web";
    /** Datasets are always published under their local identity; asset resolves them to OM. */
    private static final String IDENTITY_TYPE_LOCAL = "local";
    /** Deterministic runId prefix; keeps runIds from colliding with other producers' uuids. */
    private static final String RUN_ID_DERIVATION_PREFIX = "stweb-lineage:";

    private OpenLineageEventFactory() {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Derives the OpenLineage runId deterministically from the Engine job id, so START, RUNNING
     * heartbeats and the terminal event of one instance share one runId even across a restart:
     * {@code UUID.nameUUIDFromBytes("stweb-lineage:" + engineJobId)}.
     */
    public static UUID deriveRunId(String engineJobId) {
        return UUID.nameUUIDFromBytes(
                (RUN_ID_DERIVATION_PREFIX + engineJobId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Renders one {@code RunEvent}. Pure function: no I/O and no mutable global state.
     */
    public static ObjectNode toRunEvent(OpenLineageSnapshot snapshot) {
        ObjectNode event = OBJECT_MAPPER.createObjectNode();

        event.put("eventType", snapshot.eventType().name());
        event.put("eventTime", snapshot.eventTime().toString());
        event.set("run", buildRun(snapshot));
        event.set("job", buildJob(snapshot));
        event.set("inputs", buildDatasets(snapshot.inputs()));
        ArrayNode outputs = buildDatasets(snapshot.outputs());
        attachColumnLineage(outputs, snapshot);
        event.set("outputs", outputs);
        event.put("producer", PRODUCER);
        event.put("schemaURL", RUN_EVENT_SCHEMA_URL);
        return event;
    }

    private static ObjectNode buildRun(OpenLineageSnapshot snapshot) {
        ObjectNode run = OBJECT_MAPPER.createObjectNode();
        run.put("runId", deriveRunId(snapshot.engineJobId()).toString());

        ObjectNode facets = run.putObject("facets");
        ObjectNode seatunnel = facets.putObject("seatunnel");
        seatunnel.put("_producer", PRODUCER);
        seatunnel.put("_schemaURL", SEATUNNEL_RUN_FACET_SCHEMA_URL);
        putNumber(seatunnel, "instanceId", snapshot.instanceId());
        putNumber(seatunnel, "jobDefinitionId", snapshot.jobDefinitionId());
        seatunnel.put("engineJobId", snapshot.engineJobId());
        seatunnel.put("streaming", snapshot.streaming());
        putString(seatunnel, "runMode", snapshot.runMode());
        putString(seatunnel, "jobType", snapshot.jobType());
        putString(seatunnel, "prevStatus", snapshot.prevStatus());
        putString(seatunnel, "errorMessage", snapshot.errorMessage());
        putString(seatunnel, "lastHeartbeat", snapshot.lastHeartbeat());
        return run;
    }

    private static ObjectNode buildJob(OpenLineageSnapshot snapshot) {
        ObjectNode job = OBJECT_MAPPER.createObjectNode();
        job.put("namespace", JOB_NAMESPACE);
        String jobName = snapshot.jobName();
        job.put("name", jobName == null || jobName.isBlank()
                ? "instance-" + snapshot.instanceId()
                : jobName);
        return job;
    }

    private static ArrayNode buildDatasets(List<OpenLineageSnapshot.Dataset> datasets) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        if (datasets == null) {
            return array;
        }
        for (OpenLineageSnapshot.Dataset dataset : datasets) {
            if (dataset == null) {
                continue;
            }
            array.add(buildDataset(dataset));
        }
        return array;
    }

    private static ObjectNode buildDataset(OpenLineageSnapshot.Dataset dataset) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("namespace", dataset.namespace());
        node.put("name", dataset.name());

        ObjectNode facets = node.putObject("facets");
        ObjectNode datasource = facets.putObject("seatunnelDatasource");
        datasource.put("_producer", PRODUCER);
        datasource.put("_schemaURL", SEATUNNEL_DATASOURCE_FACET_SCHEMA_URL);
        putNumber(datasource, "datasourceId", dataset.datasourceId());
        putString(datasource, "dbTypeName", dataset.dbTypeName());
        String serviceName = serviceName(dataset.datasourceId());
        putString(datasource, "serviceName", serviceName);
        datasource.put("identityType", IDENTITY_TYPE_LOCAL);

        // Standard facet consumed by OM 2.0.4 and the asset precheck: the datasource name is
        // resolved as the database service name, which lets the consumer build the exact table
        // FQN without a namespaceToServiceMapping entry.
        if (serviceName != null) {
            ObjectNode standardDatasource = facets.putObject("datasource");
            standardDatasource.put("_producer", PRODUCER);
            standardDatasource.put("_schemaURL", DATASOURCE_FACET_SCHEMA_URL);
            standardDatasource.put("name", serviceName);
        }
        return node;
    }

    /**
     * OpenMetadata service FQN derived from the immutable local datasource id. This is a pure
     * local derivation (no OpenMetadata I/O), used only to give consumers an exact service name.
     */
    private static String serviceName(Long datasourceId) {
        return datasourceId == null || datasourceId <= 0
                ? null
                : MetadataStableName.serviceFqn(datasourceId);
    }

    /**
     * Attaches the standard {@code columnLineage} facet to every output dataset that has one,
     * keyed by the output field name as the OpenLineage spec requires. Absent when no field was
     * mapped or when the source table of a column is unknown, which marks a table-level-only run.
     */
    private static void attachColumnLineage(ArrayNode outputs, OpenLineageSnapshot snapshot) {
        List<OpenLineageSnapshot.OutputLineage> lineages = snapshot.lineages();
        if (lineages == null || lineages.isEmpty()) {
            return;
        }
        List<OpenLineageSnapshot.Dataset> datasets = snapshot.outputs();
        for (int index = 0; index < outputs.size() && index < datasets.size(); index++) {
            OpenLineageSnapshot.Dataset output = datasets.get(index);
            OpenLineageSnapshot.OutputLineage lineage = findLineage(lineages, output);
            if (lineage == null || lineage.columns().isEmpty()) {
                continue;
            }
            ObjectNode datasetNode = (ObjectNode) outputs.get(index);
            ObjectNode facets = (ObjectNode) datasetNode.get("facets");
            ObjectNode columnLineage = facets.putObject("columnLineage");
            columnLineage.put("_producer", PRODUCER);
            columnLineage.put("_schemaURL", COLUMN_LINEAGE_FACET_SCHEMA_URL);

            ObjectNode fields = columnLineage.putObject("fields");
            for (Map.Entry<String, List<OpenLineageSnapshot.SourceColumn>> column
                    : new TreeMap<>(lineage.columns()).entrySet()) {
                ObjectNode target = fields.putObject(column.getKey());
                ArrayNode inputFields = target.putArray("inputFields");
                for (OpenLineageSnapshot.SourceColumn source : column.getValue()) {
                    ObjectNode inputField = inputFields.addObject();
                    inputField.put("namespace", source.dataset().namespace() == null
                            ? "" : source.dataset().namespace());
                    inputField.put("name", source.dataset().name() == null
                            ? "" : source.dataset().name());
                    inputField.put("field", source.column());
                }
            }
        }
    }

    /** The column lineage of one output dataset, matched by its local identity. */
    private static OpenLineageSnapshot.OutputLineage findLineage(
            List<OpenLineageSnapshot.OutputLineage> lineages, OpenLineageSnapshot.Dataset output) {
        for (OpenLineageSnapshot.OutputLineage lineage : lineages) {
            if (lineage.output() == null) {
                continue;
            }
            if (java.util.Objects.equals(lineage.output().namespace(), output.namespace())
                    && java.util.Objects.equals(lineage.output().name(), output.name())) {
                return lineage;
            }
        }
        return null;
    }

    private static void putString(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static void putNumber(ObjectNode node, String field, Number value) {
        if (value != null) {
            node.put(field, value.longValue());
        }
    }
}
