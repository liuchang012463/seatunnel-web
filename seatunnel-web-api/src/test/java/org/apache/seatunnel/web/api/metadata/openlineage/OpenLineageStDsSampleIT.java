package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.seatunnel.web.api.metadata.FieldMappingExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MANUAL sample for the OM 2.0.4 service {@code st_ds_22416919044448}.
 *
 * <p>
 * It renders one real COMPLETE event through the production {@link OpenLineageEventFactory},
 * adds the full input/output schema facets so both sides of the column lineage are visible,
 * produces it to {@code meta_lineage_topic}, and reads the same runId back. The schema facets
 * are sample-only: the production bridge does not fetch full table schemas; OM already owns them.
 * </p>
 *
 * <p>
 * Run with {@code LINEAGE_BRIDGE_SAMPLE=true}. Regular test runs skip it.
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "LINEAGE_BRIDGE_SAMPLE", matches = "true")
class OpenLineageStDsSampleIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long DATASOURCE_ID = 22416919044448L;
    private static final String DATASOURCE_NAME = "82.157.22.233_13306";
    private static final String SERVICE_NAME = "st_ds_22416919044448";
    private static final String INPUT_TABLE = "om_demo.chatbi.wx_mp_user";
    private static final String OUTPUT_TABLE = "om_demo.chatbi.ac_user";
    private static final String SCHEMA_FACET_SCHEMA_URL =
            "https://openlineage.io/spec/facets/1-1-1/SchemaDatasetFacet.json";

    private static final List<SchemaField> WX_MP_USER_FIELDS = List.of(
            field("id", "INT"),
            field("appid", "VARCHAR"),
            field("nickname", "VARCHAR"),
            field("sex", "TINYINT"),
            field("head_img_url", "VARCHAR"),
            field("union_id", "VARCHAR"),
            field("open_id", "VARCHAR"),
            field("country", "VARCHAR"),
            field("province", "VARCHAR"),
            field("city", "VARCHAR"),
            field("is_subscribe", "TINYINT"),
            field("user_id", "BIGINT"),
            field("update_time", "DATETIME"),
            field("create_time", "DATETIME"));

    private static final List<SchemaField> AC_USER_FIELDS = List.of(
            field("id", "BIGINT"),
            field("name", "VARCHAR"),
            field("avatar", "VARCHAR"),
            field("gender", "TINYINT"),
            field("email", "VARCHAR"),
            field("phone_number", "VARCHAR"),
            field("password", "VARCHAR"),
            field("reg_channel", "VARCHAR"),
            field("last_login_channel", "TINYINT"),
            field("last_login_time", "DATETIME"),
            field("last_login_ip", "VARCHAR"),
            field("password_type", "TINYINT"),
            field("deleted", "TINYINT"),
            field("create_time", "DATETIME"),
            field("update_time", "DATETIME"));

    @Test
    void produceOneRealSampleAndReadItBack() throws Exception {
        String bootstrap = env("LINEAGE_BRIDGE_KAFKA_BOOTSTRAP_SERVERS",
                "127.0.0.1:19092,127.0.0.1:19093,127.0.0.1:19094");
        String topic = env("LINEAGE_BRIDGE_SOURCE_TOPIC", "meta_lineage_topic");
        String username = env("LINEAGE_BRIDGE_KAFKA_SASL_USERNAME", "kafka-admin");
        String password = env("LINEAGE_BRIDGE_KAFKA_SASL_PASSWORD", "Kafka@2024Secure!");

        LineageBridgeProperties properties = new LineageBridgeProperties();
        properties.getKafka().setEnabled(true);
        properties.getKafka().setBootstrapServers(bootstrap);
        properties.getKafka().setSourceTopic(topic);
        properties.getKafka().setSaslUsername(username);
        properties.getKafka().setSaslPassword(password);
        // The broker may have automatic topic creation turned off.
        new OpenLineageTopicProvisioner(properties).ensureTopic(topic);

        String engineJobId = "stweb-stds-sample-" + System.currentTimeMillis();
        String runId = OpenLineageEventFactory.deriveRunId(engineJobId).toString();

        // The event is built the way the bridge builds it: the submitted configuration is parsed
        // into datasets, and every mapped column is attributed to the table it comes from.
        Config runtimeConfig = ConfigFactory.parseString("""
                source {
                    Jdbc {
                        url="jdbc:mysql://82.157.22.233:13306/om_demo"
                        table_path="om_demo.chatbi.wx_mp_user"
                    }
                }
                transform {
                    mapped {
                        source_table_name="om_demo.chatbi.wx_mp_user"
                        result_table_name="om_demo.chatbi.ac_user"
                        query="select id as id, nickname as name, head_img_url as avatar, sex as gender, update_time as last_login_time, create_time as create_time, update_time as update_time from om_demo.chatbi.wx_mp_user"
                    }
                }
                sink {
                    Jdbc {
                        url="jdbc:mysql://82.157.22.233:13306/om_demo"
                        table="om_demo.chatbi.ac_user"
                    }
                }
                """);
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(runtimeConfig);
        assertEquals(1, topology.sources().size());
        assertEquals(1, topology.sinks().size());
        List<OpenLineageSnapshot.Dataset> inputs = List.of(new OpenLineageSnapshot.Dataset(
                DATASOURCE_ID, "MYSQL", DATASOURCE_NAME,
                topology.sources().get(0).tables().get(0)));
        List<OpenLineageSnapshot.Dataset> outputs = List.of(new OpenLineageSnapshot.Dataset(
                DATASOURCE_ID, "MYSQL", DATASOURCE_NAME,
                topology.sinks().get(0).tables().get(0)));
        assertEquals(INPUT_TABLE, inputs.get(0).name());
        assertEquals(OUTPUT_TABLE, outputs.get(0).name());
        List<OpenLineageSnapshot.OutputLineage> lineages = OpenLineageColumnLineageBuilder.build(
                inputs, outputs, FieldMappingExtractor.extractDetailed(runtimeConfig));
        assertEquals(1, lineages.size());
        assertEquals(7, lineages.get(0).columns().size());

        OpenLineageSnapshot snapshot = new OpenLineageSnapshot(
                OpenLineageEventFactory.EventType.COMPLETE,
                Instant.now(),
                engineJobId,
                1024L, 86L, "chatbi_sync_ac_user",
                false, "BATCH", "BATCH",
                "RUNNING", null, null,
                inputs,
                outputs,
                lineages);

        ObjectNode event = OpenLineageEventFactory.toRunEvent(snapshot);
        attachSchema(event.path("inputs").get(0), WX_MP_USER_FIELDS);
        attachSchema(event.path("outputs").get(0), AC_USER_FIELDS);
        String eventJson = event.toPrettyString();

        System.out.println("[SAMPLE] topic=" + topic + ", runId=" + runId);
        System.out.println("[SAMPLE] event=");
        System.out.println(eventJson);

        RecordMetadata metadata;
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(OpenLineageKafkaBridge.producerProperties(properties))) {
            metadata = producer.send(new ProducerRecord<>(topic, null, eventJson)).get();
        }
        System.out.println("[SAMPLE] produced partition=" + metadata.partition()
                + " offset=" + metadata.offset());

        String found = consume(topic, runId, properties);
        assertNotNull(found, "produced runId=" + runId + " was not seen back from " + topic);
        System.out.println("[SAMPLE] consumed event=");
        System.out.println(MAPPER.readTree(found).toPrettyString());

        JsonNode consumed = MAPPER.readTree(found);
        JsonNode input = consumed.path("inputs").get(0);
        JsonNode output = consumed.path("outputs").get(0);
        assertEquals("COMPLETE", consumed.path("eventType").asText());
        assertEquals(SERVICE_NAME, input.path("facets").path("datasource").path("name").asText());
        assertEquals(SERVICE_NAME, output.path("facets").path("datasource").path("name").asText());
        assertEquals(DATASOURCE_ID,
                input.path("facets").path("seatunnelDatasource").path("datasourceId").asLong());
        assertEquals(SERVICE_NAME,
                input.path("facets").path("seatunnelDatasource").path("serviceName").asText());
        assertEquals(WX_MP_USER_FIELDS.size(),
                input.path("facets").path("schema").path("fields").size());
        assertEquals(AC_USER_FIELDS.size(),
                output.path("facets").path("schema").path("fields").size());

        JsonNode lineageFields = output.path("facets").path("columnLineage").path("fields");
        assertEquals(lineages.get(0).columns().size(), lineageFields.size());
        assertLineage(lineageFields, "id", "id");
        assertLineage(lineageFields, "name", "nickname");
        assertLineage(lineageFields, "avatar", "head_img_url");
        assertLineage(lineageFields, "gender", "sex");
        assertLineage(lineageFields, "last_login_time", "update_time");
        assertLineage(lineageFields, "create_time", "create_time");
        assertLineage(lineageFields, "update_time", "update_time");
        System.out.println("[SAMPLE] verified service=" + SERVICE_NAME
                + ", inputColumns=" + WX_MP_USER_FIELDS.size()
                + ", outputColumns=" + AC_USER_FIELDS.size()
                + ", lineageColumns=" + lineages.get(0).columns().size());
    }

    private static String consume(String topic, String runId, LineageBridgeProperties properties) {
        Properties consumerProps = new Properties();
        consumerProps.putAll(OpenLineageKafkaBridge.producerProperties(properties));
        consumerProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG,
                "stweb-stds-sample-" + UUID.randomUUID());
        consumerProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(2))) {
                    if (record.value() != null && record.value().contains(runId)) {
                        System.out.println("[SAMPLE] consumed partition=" + record.partition()
                                + " offset=" + record.offset());
                        return record.value();
                    }
                }
            }
        }
        return null;
    }

    private static void assertLineage(JsonNode fields, String outputField, String inputField) {
        JsonNode input = fields.path(outputField).path("inputFields").get(0);
        assertEquals(inputField, input.path("field").asText());
        assertEquals(INPUT_TABLE, input.path("name").asText());
        assertEquals(DATASOURCE_NAME, input.path("namespace").asText());
    }

    private static void attachSchema(JsonNode dataset, List<SchemaField> fields) {
        ObjectNode facets = (ObjectNode) dataset.get("facets");
        ObjectNode schema = facets.putObject("schema");
        schema.put("_producer", OpenLineageEventFactory.PRODUCER);
        schema.put("_schemaURL", SCHEMA_FACET_SCHEMA_URL);
        ArrayNode array = schema.putArray("fields");
        for (SchemaField field : fields) {
            ObjectNode fieldNode = array.addObject();
            fieldNode.put("name", field.name());
            fieldNode.put("type", field.type());
        }
    }

    private static SchemaField field(String name, String type) {
        return new SchemaField(name, type);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record SchemaField(String name, String type) {
    }
}
