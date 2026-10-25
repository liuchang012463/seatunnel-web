package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MANUAL smoke test against the real (three-broker local) Kafka deployment.
 *
 * <p>
 * Skipped unless {@code LINEAGE_BRIDGE_SMOKE=true} is set, so regular runs never touch the
 * broker. It produces one OpenLineage START event via the bridge's real producer properties
 * and then reads it back from the real {code meta_lineage_topic}, matching on the deterministic
 * runId.
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "LINEAGE_BRIDGE_SMOKE", matches = "true")
class OpenLineageKafkaSmokeIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void produceOneRealRunEventAndReadItBack() throws Exception {
        String bootstrap = env("LINEAGE_BRIDGE_KAFKA_BOOTSTRAP_SERVERS",
                "127.0.0.1:19092,127.0.0.1:19093,127.0.0.1:19094");
        String topic = env("LINEAGE_BRIDGE_SOURCE_TOPIC", "meta_lineage_topic");
        String username = env("LINEAGE_BRIDGE_KAFKA_SASL_USERNAME", "kafka-admin");
        String password = env("LINEAGE_BRIDGE_KAFKA_SASL_PASSWORD", "");

        LineageBridgeProperties properties = new LineageBridgeProperties();
        properties.getKafka().setEnabled(true);
        properties.getKafka().setBootstrapServers(bootstrap);
        properties.getKafka().setSourceTopic(topic);
        properties.getKafka().setSaslUsername(username);
        properties.getKafka().setSaslPassword(password);
        // The broker may have automatic topic creation turned off.
        new OpenLineageTopicProvisioner(properties).ensureTopic(topic);

        // A marker engine job id keeps this run unique and easily searchable.
        String engineJobId = "stweb-smoke-" + System.currentTimeMillis();
        OpenLineageEventFactory.EventType eventType = OpenLineageEventFactory.EventType.START;
        OpenLineageSnapshot snapshot = new OpenLineageSnapshot(
                eventType,
                Instant.now(),
                engineJobId,
                0L, 0L, "lineage-bridge-smoke-test",
                false, "MANUAL", "BATCH",
                null, null, null,
                List.of(new OpenLineageSnapshot.Dataset(0L, "MYSQL", "smoke-datasource", "smoke.source_table")),
                List.of(new OpenLineageSnapshot.Dataset(1L, "DORIS", "smoke-datasource", "smoke.sink_table")),
                List.of(new OpenLineageSnapshot.OutputLineage(
                        new OpenLineageSnapshot.Dataset(1L, "DORIS", "smoke-datasource", "smoke.sink_table"),
                        Map.of("smoke_out", List.of(new OpenLineageSnapshot.SourceColumn(
                                new OpenLineageSnapshot.Dataset(
                                        0L, "MYSQL", "smoke-datasource", "smoke.source_table"),
                                "smoke_in"))))));
        String runId = OpenLineageEventFactory.deriveRunId(0L, engineJobId).toString();
        String eventJson = OpenLineageEventFactory.toRunEvent(snapshot).toString();
        System.out.println("[SMOKE] producing to " + topic + ", runId=" + runId);
        System.out.println("[SMOKE] event=" + eventJson);

        // Produce with exactly the properties the bridge would use (acks=all, SASL PLAIN).
        RecordMetadata metadata;
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(OpenLineageKafkaBridge.producerProperties(properties))) {
            metadata = producer.send(new ProducerRecord<>(topic, null, eventJson)).get();
        }
        System.out.println("[SMOKE] produced, partition=" + metadata.partition()
                + " offset=" + metadata.offset());

        // Read it back with a fresh consumer to confirm the round trip.
        Properties consumerProps = new Properties();
        consumerProps.putAll(OpenLineageKafkaBridge.producerProperties(properties));
        consumerProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG,
                "stweb-lineage-smoke-" + UUID.randomUUID());
        consumerProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        String foundValue = null;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && foundValue == null) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofSeconds(2))) {
                    if (record.value() != null && record.value().contains(runId)) {
                        foundValue = record.value();
                        System.out.println("[SMOKE] consumed from partition=" + record.partition()
                                + " offset=" + record.offset());
                        break;
                    }
                }
            }
        }
        assertNotNull(foundValue, "produced runId=" + runId + " was not seen back from the topic");

        JsonNode event = MAPPER.readTree(foundValue);
        assertEquals("START", event.path("eventType").asText());
        assertEquals("lineage-bridge-smoke-test", event.path("job").path("name").asText());
        assertEquals("smoke_out",
                event.path("outputs").get(0).path("facets").path("columnLineage")
                        .path("fields").fieldNames().next());
        assertEquals("smoke-datasource", event.path("inputs").get(0).path("namespace").asText());
        System.out.println("[SMOKE] verified OK");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
