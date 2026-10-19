package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLineageEventFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final OpenLineageSnapshot.Dataset MYSQL_SOURCE =
            new OpenLineageSnapshot.Dataset(7L, "MYSQL", "bi_src_mysql", "ds_1.orders");
    private static final OpenLineageSnapshot.Dataset DORIS_SINK =
            new OpenLineageSnapshot.Dataset(11L, "DORIS", "bi_tidb_doris", "ods.orders_dwd");

    @Test
    void rendersASpecConformantStartEvent() {
        OpenLineageSnapshot snapshot = new OpenLineageSnapshot(
                OpenLineageEventFactory.EventType.START,
                Instant.ofEpochSecond(1790000000),
                "engine-job-42",
                1024L, 86L, "ods_order_sync",
                false, "MANUAL", "BATCH",
                null, null, null,
                MYSQL_SOURCE, DORIS_SINK,
                Map.of("create_date", Set.of("create_time")));

        JsonNode event = MAPPER.convertValue(
                OpenLineageEventFactory.toRunEvent(snapshot), JsonNode.class);

        // Root skeleton required by the spec.
        assertEquals("START", event.path("eventType").asText());
        assertFalse(event.path("eventTime").asText().isEmpty());
        assertFalse(event.path("schemaURL").asText().isEmpty());
        assertEquals(OpenLineageEventFactory.PRODUCER, event.path("producer").asText());

        // Run + custom facet carrying the engine-native ids.
        UUID runId = UUID.fromString(event.path("run").path("runId").asText());
        assertEquals(
                UUID.nameUUIDFromBytes("stweb-lineage:engine-job-42".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                runId);
        JsonNode seatunnel = event.path("run").path("facets").path("seatunnel");
        assertEquals(1024L, seatunnel.path("instanceId").asLong());
        assertEquals(86L, seatunnel.path("jobDefinitionId").asLong());
        assertEquals("engine-job-42", seatunnel.path("engineJobId").asText());
        assertFalse(seatunnel.path("streaming").asBoolean());
        assertEquals("MANUAL", seatunnel.path("runMode").asText());
        assertEquals("BATCH", seatunnel.path("jobType").asText());
        assertTrue(seatunnel.path("prevStatus").isMissingNode());
        assertTrue(seatunnel.path("errorMessage").isMissingNode());
        assertTrue(seatunnel.path("lastHeartbeat").isMissingNode());

        // Job identity.
        assertEquals("seatunnel-web", event.path("job").path("namespace").asText());
        assertEquals("ods_order_sync", event.path("job").path("name").asText());

        // Datasets carry only the local identity; asset resolves them to OM.
        JsonNode input = event.path("inputs").get(0);
        assertEquals("bi_src_mysql", input.path("namespace").asText());
        assertEquals("ds_1.orders", input.path("name").asText());
        JsonNode inputFacet = input.path("facets").path("seatunnelDatasource");
        assertEquals(7L, inputFacet.path("datasourceId").asLong());
        assertEquals("MYSQL", inputFacet.path("dbTypeName").asText());
        assertEquals("local", inputFacet.path("identityType").asText());

        JsonNode output = event.path("outputs").get(0);
        assertEquals("bi_tidb_doris", output.path("namespace").asText());
        assertEquals("ods.orders_dwd", output.path("name").asText());

        // Standard columnLineage facet, keyed by the output field per the spec.
        JsonNode columnLineage = output.path("facets").path("columnLineage");
        assertEquals(OpenLineageEventFactory.COLUMN_LINEAGE_FACET_SCHEMA_URL,
                columnLineage.path("_schemaURL").asText());
        JsonNode createDate = columnLineage.path("fields").path("create_date");
        JsonNode inputField = createDate.path("inputFields").get(0);
        assertEquals("bi_src_mysql", inputField.path("namespace").asText());
        assertEquals("ds_1.orders", inputField.path("name").asText());
        assertEquals("create_time", inputField.path("field").asText());
    }

    @Test
    void runIdIsDeterministicForTheSameEngineJobId() {
        UUID first = OpenLineageEventFactory.deriveRunId("8e12af37-9abc");
        UUID second = OpenLineageEventFactory.deriveRunId("8e12af37-9abc");
        UUID other = OpenLineageEventFactory.deriveRunId("different-engine-job");

        assertEquals(first, second);
        assertFalse(first.equals(other));
    }

    @Test
    void omitsTheColumnLineageFacetWhenNoFieldWasMapped() {
        OpenLineageSnapshot snapshot = new OpenLineageSnapshot(
                OpenLineageEventFactory.EventType.START,
                Instant.now(),
                "engine-job-43",
                1L, 2L, "t2",
                false, null, null,
                null, null, null,
                MYSQL_SOURCE, DORIS_SINK,
                Map.of());

        JsonNode event = MAPPER.convertValue(
                OpenLineageEventFactory.toRunEvent(snapshot), JsonNode.class);
        // Missing facet = table-level lineage; dataset facets stay.
        assertTrue(event.path("outputs").get(0).path("facets").path("columnLineage").isMissingNode());
        assertEquals("ods.orders_dwd", event.path("outputs").get(0).path("name").asText());
    }

    @Test
    void terminalEventsCarryPrevStatusAndErrorWhileRunningCarriesHeartbeat() {
        OpenLineageSnapshot failed = new OpenLineageSnapshot(
                OpenLineageEventFactory.EventType.FAIL,
                Instant.now(),
                "engine-job-44",
                3L, 4L, "t3",
                false, null, null,
                "RUNNING", "Engine crashed", null,
                MYSQL_SOURCE, DORIS_SINK,
                Map.of());
        OpenLineageSnapshot heartbeat = new OpenLineageSnapshot(
                OpenLineageEventFactory.EventType.RUNNING,
                Instant.now(),
                "engine-job-44",
                3L, 4L, "t3",
                true, null, null,
                null, null, "2026-10-19T10:00:00Z",
                MYSQL_SOURCE, DORIS_SINK,
                Map.of());

        JsonNode failedEvent = MAPPER.convertValue(
                OpenLineageEventFactory.toRunEvent(failed), JsonNode.class);
        JsonNode seatunnel = failedEvent.path("run").path("facets").path("seatunnel");
        assertEquals("RUNNING", seatunnel.path("prevStatus").asText());
        assertEquals("Engine crashed", seatunnel.path("errorMessage").asText());
        assertEquals("FAIL", failedEvent.path("eventType").asText());
        assertTrue(seatunnel.path("lastHeartbeat").isMissingNode());

        JsonNode heartbeatEvent = MAPPER.convertValue(
                OpenLineageEventFactory.toRunEvent(heartbeat), JsonNode.class);
        JsonNode heartbeatFacet = heartbeatEvent.path("run").path("facets").path("seatunnel");
        assertEquals("2026-10-19T10:00:00Z", heartbeatFacet.path("lastHeartbeat").asText());
        assertTrue(heartbeatFacet.path("prevStatus").isMissingNode());
        assertTrue(heartbeatFacet.path("errorMessage").isMissingNode());
        assertTrue(heartbeatFacet.path("streaming").asBoolean());
        // Same run across process lifetimes: heartbeat and terminal share the runId.
        assertEquals(failedEvent.path("run").path("runId").asText(),
                heartbeatEvent.path("run").path("runId").asText());
    }

    @Test
    void neverEmitsOpenMetadataRequestStructure() {
        OpenLineageSnapshot snapshot = new OpenLineageSnapshot(
                OpenLineageEventFactory.EventType.COMPLETE,
                Instant.now(),
                "engine-job-45",
                9L, 9L, "t9",
                false, null, null,
                "RUNNING", null, null,
                MYSQL_SOURCE, DORIS_SINK,
                new TreeMap<>(Map.of("c", Set.of("a", "b"))));

        JsonNode event = MAPPER.convertValue(
                OpenLineageEventFactory.toRunEvent(snapshot), JsonNode.class);

        // The bridge shares zero structure with OpenMetadataJobLineageService.buildLineageRequest.
        assertFalse(event.has("edge"));
        assertFalse(event.has("fromEntity"));
        assertFalse(event.has("toEntity"));
        assertFalse(event.has("lineageDetails"));
        assertFalse(event.path("outputs").get(0).path("facets").has("columnsLineage"));
        assertNotNull(event);
    }
}
