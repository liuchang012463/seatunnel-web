package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageSnapshot.Dataset;
import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageTableFqnResolver.TableIdentity;
import org.apache.seatunnel.web.common.utils.MetadataStableName;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Renders OpenLineage {@code RunEvent} JSON from a {@link OpenLineageSnapshot}.
 *
 * <p>
 * The output follows the OpenLineage v1 specification only: the run/job/dataset skeleton, the
 * standard {@code datasource} and {@code columnLineage} facets, and two custom facets
 * ({@code seatunnel} on the run, {@code seatunnelDatasource} on every dataset) carrying the
 * engine-native identities.
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
            "https://openlineage.io/spec/facets/1-1-0/ColumnLineageDatasetFacet.json";
    static final String DATASOURCE_FACET_SCHEMA_URL =
            "https://openlineage.io/spec/facets/1-0-0/DatasourceDatasetFacet.json";
    /** Custom facets are not part of the official spec namespace, so these URLs are project-local. */
    static final String SEATUNNEL_RUN_FACET_SCHEMA_URL =
            "https://github.com/weifuwan/seatunnel-web/spec/facets/1-0-0/SeatunnelRunFacet.json";
    static final String SEATUNNEL_DATASOURCE_FACET_SCHEMA_URL =
            "https://github.com/weifuwan/seatunnel-web/spec/facets/1-0-0/SeatunnelDatasourceFacet.json";

    /** Job namespace for every event the web publishes from this module. */
    private static final String JOB_NAMESPACE = "seatunnel-web";
    private static final String IDENTITY_TYPE_LOCAL = "local";
    private static final String IDENTITY_TYPE_FQN = "fqn";
    /** Deterministic runId prefix; keeps runIds from colliding with other producers' uuids. */
    private static final String RUN_ID_DERIVATION_PREFIX = "stweb-lineage:";

    private OpenLineageEventFactory() {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Derives the OpenLineage runId deterministically from the instance id and the Engine job id,
     * so START, RUNNING heartbeats and the terminal event of one instance share one runId even
     * across a restart. The instance id keeps a savepoint-restored run, which reuses the previous
     * Engine job id, from colliding with the run it was restored from.
     * {@code UUID.nameUUIDFromBytes("stweb-lineage:" + instanceId + ":" + engineJobId)}.
     */
    public static UUID deriveRunId(Long instanceId, String engineJobId) {
        String identity = RUN_ID_DERIVATION_PREFIX
                + (instanceId == null ? "" : instanceId)
                + ":"
                + engineJobId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Renders one {@code RunEvent}. Pure function: no I/O and no mutable global state.
     */
    public static ObjectNode toRunEvent(OpenLineageSnapshot snapshot) {
        return toRunEvent(snapshot, Map.of());
    }

    static ObjectNode toRunEvent(OpenLineageSnapshot snapshot, Map<Dataset, TableIdentity> identities) {
        return toRunEvent(snapshot, identities, deriveRunId(snapshot.instanceId(), snapshot.engineJobId()),
                snapshot.inputs(), snapshot.outputs());
    }

    static ObjectNode toTableRunEvent(
            OpenLineageSnapshot snapshot, Map<Dataset, TableIdentity> identities, Dataset source, Dataset sink) {
        // The same logical job owns separate OpenLineage runs for its independent table copies.
        // Derive the run from local paths so START and COMPLETE agree even across a metadata scan.
        String key = OBJECT_MAPPER.createArrayNode()
                .add(deriveRunId(snapshot.instanceId(), snapshot.engineJobId()).toString())
                .add(source.datasourceId()).add(source.name())
                .add(sink.datasourceId()).add(sink.name()).toString();
        UUID runId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return toRunEvent(snapshot, identities, runId, List.of(source), List.of(sink));
    }

    private static ObjectNode toRunEvent(OpenLineageSnapshot snapshot, Map<Dataset, TableIdentity> identities,
                                         UUID runId, List<Dataset> sources, List<Dataset> sinks) {
        ObjectNode event = OBJECT_MAPPER.createObjectNode();

        event.put("eventType", snapshot.eventType().name());
        event.put("eventTime", snapshot.eventTime().toString());
        event.set("run", buildRun(snapshot, runId));
        event.set("job", buildJob(snapshot));
        event.set("inputs", buildDatasets(sources, identities));
        ArrayNode outputs = buildDatasets(sinks, identities);
        attachColumnLineage(outputs, sinks, snapshot.lineages(), identities);
        event.set("outputs", outputs);
        event.put("producer", PRODUCER);
        event.put("schemaURL", RUN_EVENT_SCHEMA_URL);
        return event;
    }

    private static ObjectNode buildRun(OpenLineageSnapshot snapshot, UUID runId) {
        ObjectNode run = OBJECT_MAPPER.createObjectNode();
        run.put("runId", runId.toString());

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

    private static ArrayNode buildDatasets(List<Dataset> datasets, Map<Dataset, TableIdentity> identities) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        if (datasets == null) {
            return array;
        }
        for (Dataset dataset : datasets) {
            if (dataset == null) {
                continue;
            }
            array.add(buildDataset(dataset, identities));
        }
        return array;
    }

    private static ObjectNode buildDataset(Dataset dataset, Map<Dataset, TableIdentity> identities) {
        TableIdentity identity = identities.get(dataset);
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("namespace", dataset.namespace());
        node.put("name", datasetName(dataset, identities));

        ObjectNode facets = node.putObject("facets");
        ObjectNode datasource = facets.putObject("seatunnelDatasource");
        datasource.put("_producer", PRODUCER);
        datasource.put("_schemaURL", SEATUNNEL_DATASOURCE_FACET_SCHEMA_URL);
        putNumber(datasource, "datasourceId", dataset.datasourceId());
        putString(datasource, "dbTypeName", dataset.dbTypeName());
        String serviceName = identity == null ? serviceName(dataset.datasourceId()) : identity.serviceName();
        putString(datasource, "serviceName", serviceName);
        datasource.put("identityType", identity == null ? IDENTITY_TYPE_LOCAL : IDENTITY_TYPE_FQN);

        // Standard facet consumed by OM 2.0.4 and the asset precheck: the datasource name is
        // resolved as the database service name, which lets the consumer build the exact table
        // FQN without a namespaceToServiceMapping entry. The uri is the masked connection url,
        // the facet's second standard field.
        if (serviceName != null) {
            ObjectNode standardDatasource = facets.putObject("datasource");
            standardDatasource.put("_producer", PRODUCER);
            standardDatasource.put("_schemaURL", DATASOURCE_FACET_SCHEMA_URL);
            standardDatasource.put("name", serviceName);
            putString(standardDatasource, "uri", dataset.uri());
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
    private static void attachColumnLineage(
            ArrayNode outputs, List<Dataset> datasets,
            List<OpenLineageSnapshot.OutputLineage> lineages, Map<Dataset, TableIdentity> identities) {
        if (lineages == null || lineages.isEmpty()) {
            return;
        }
        for (int index = 0; index < outputs.size() && index < datasets.size(); index++) {
            Dataset output = datasets.get(index);
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
                    // Assert identifies the source by the same namespace/name pair as the input dataset.
                    String name = datasetName(source.dataset(), identities);
                    inputField.put("name", name == null ? "" : name);
                    inputField.put("field", source.column());
                }
            }
        }
    }

    private static String datasetName(Dataset dataset, Map<Dataset, TableIdentity> identities) {
        TableIdentity identity = identities.get(dataset);
        return identity == null ? dataset.name() : identity.fqn();
    }

    /** The column lineage of one output dataset, matched by its local identity. */
    private static OpenLineageSnapshot.OutputLineage findLineage(
            List<OpenLineageSnapshot.OutputLineage> lineages, Dataset output) {
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
