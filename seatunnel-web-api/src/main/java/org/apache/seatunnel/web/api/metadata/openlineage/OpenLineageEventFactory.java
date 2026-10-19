package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Renders OpenLineage {@code RunEvent} JSON from a {@link OpenLineageSnapshot}.
 *
 * <p>
 * The output follows the OpenLineage v1 specification only: the run/job/dataset skeleton, the
 * standard {@code columnLineage} facet, and two custom facets ({@code seatunnel} on the run,
 * {@code seatunnelDatasource} on every dataset) carrying the engine-native identities. Nothing
 * here shares structure with the OpenMetadata lineage request the OM channel builds.
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
        event.set("inputs", buildDatasets(snapshot, true));
        ArrayNode outputs = buildDatasets(snapshot, false);
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

    private static ArrayNode buildDatasets(OpenLineageSnapshot snapshot, boolean inputSide) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        OpenLineageSnapshot.Dataset dataset = inputSide ? snapshot.input() : snapshot.output();
        if (dataset == null) {
            return array;
        }
        ObjectNode node = array.addObject();
        node.put("namespace", dataset.namespace());
        node.put("name", dataset.name());

        ObjectNode facets = node.putObject("facets");
        ObjectNode datasource = facets.putObject("seatunnelDatasource");
        datasource.put("_producer", PRODUCER);
        datasource.put("_schemaURL", SEATUNNEL_DATASOURCE_FACET_SCHEMA_URL);
        putNumber(datasource, "datasourceId", dataset.datasourceId());
        putString(datasource, "dbTypeName", dataset.dbTypeName());
        datasource.put("identityType", IDENTITY_TYPE_LOCAL);
        return array;
    }

    /**
     * Attaches the standard {@code columnLineage} facet to the single output dataset, keyed by the
     * output field name as the OpenLineage spec requires. Absent when no field was mapped, which
     * marks a table-level-only run.
     */
    private static void attachColumnLineage(ArrayNode outputs, OpenLineageSnapshot snapshot) {
        Map<String, Set<String>> mappings = snapshot.fieldMappings();
        OpenLineageSnapshot.Dataset input = snapshot.input();
        OpenLineageSnapshot.Dataset output = snapshot.output();
        if (mappings == null || mappings.isEmpty() || output == null) {
            return;
        }
        ObjectNode datasetNode = (ObjectNode) outputs.get(0);
        ObjectNode facets = (ObjectNode) datasetNode.get("facets");
        ObjectNode columnLineage = facets.putObject("columnLineage");
        columnLineage.put("_producer", PRODUCER);
        columnLineage.put("_schemaURL", COLUMN_LINEAGE_FACET_SCHEMA_URL);

        ObjectNode fields = columnLineage.putObject("fields");
        for (Map.Entry<String, Set<String>> mapping : new TreeMap<>(mappings).entrySet()) {
            ObjectNode target = fields.putObject(mapping.getKey());
            ArrayNode inputFields = target.putArray("inputFields");
            for (String sourceField : mapping.getValue()) {
                ObjectNode inputField = inputFields.addObject();
                inputField.put("namespace", input == null ? "" : input.namespace());
                inputField.put("name", input == null ? "" : input.name());
                inputField.put("field", sourceField);
            }
        }
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
