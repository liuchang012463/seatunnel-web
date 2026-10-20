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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANUAL sample that puts the four table level lineage cardinalities on the wire.
 *
 * <p>
 * The samples are the contract documents of the complex lineage design: they are produced to
 * {@code meta_lineage_topic} exactly as they are stored, so the consumer side can be checked
 * against the very same bytes. The files live in {@code docs/samples} and are not part of the
 * repository, so the test is skipped when they are missing.
 * </p>
 *
 * <p>
 * Run with {@code LINEAGE_BRIDGE_SAMPLE=true}. Regular test runs skip it.
 * </p>
 */
@EnabledIfEnvironmentVariable(named = "LINEAGE_BRIDGE_SAMPLE", matches = "true")
class OpenLineageCardinalitySampleIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] SAMPLE_FILES = {
            "openlineage-cardinality-1to1.json",
            "openlineage-cardinality-nto1.json",
            "openlineage-cardinality-1ton.json",
            "openlineage-cardinality-ntom.json"
    };

    @Test
    void produceTheFourCardinalitySamplesAndReadThemBack() throws Exception {
        String bootstrap = env("LINEAGE_BRIDGE_KAFKA_BOOTSTRAP_SERVERS",
                "127.0.0.1:19092,127.0.0.1:19093,127.0.0.1:19094");
        String topic = env("LINEAGE_BRIDGE_SOURCE_TOPIC", "meta_lineage_topic");
        String username = env("LINEAGE_BRIDGE_KAFKA_SASL_USERNAME", "kafka-admin");
        String password = env("LINEAGE_BRIDGE_KAFKA_SASL_PASSWORD", "Kafka@2024Secure!");

        Path sampleDirectory = sampleDirectory();
        if (sampleDirectory == null) {
            System.out.println("[CARDINALITY] no sample directory found, the samples are skipped");
            return;
        }
        Map<String, String> samples = new LinkedHashMap<>();
        for (String file : SAMPLE_FILES) {
            Path path = sampleDirectory.resolve(file);
            assertTrue(Files.exists(path), "missing sample file: " + path);
            String json = Files.readString(path, StandardCharsets.UTF_8).trim();
            samples.put(file, json);
        }

        LineageBridgeProperties properties = new LineageBridgeProperties();
        properties.getKafka().setEnabled(true);
        properties.getKafka().setBootstrapServers(bootstrap);
        properties.getKafka().setSourceTopic(topic);
        properties.getKafka().setSaslUsername(username);
        properties.getKafka().setSaslPassword(password);

        Map<String, RecordMetadata> produced = new LinkedHashMap<>();
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(OpenLineageKafkaBridge.producerProperties(properties))) {
            for (Map.Entry<String, String> sample : samples.entrySet()) {
                RecordMetadata metadata = producer.send(
                        new ProducerRecord<>(topic, null, sample.getValue())).get();
                produced.put(sample.getKey(), metadata);
                JsonNode event = MAPPER.readTree(sample.getValue());
                System.out.println("[CARDINALITY] produced " + sample.getKey()
                        + " partition=" + metadata.partition() + " offset=" + metadata.offset()
                        + " runId=" + event.path("run").path("runId").asText()
                        + " inputs=" + event.path("inputs").size()
                        + " outputs=" + event.path("outputs").size());
            }
        }

        List<String> runIds = new ArrayList<>();
        for (String json : samples.values()) {
            runIds.add(MAPPER.readTree(json).path("run").path("runId").asText());
        }
        Map<String, String> consumed = consume(topic, runIds, properties);
        for (Map.Entry<String, String> sample : samples.entrySet()) {
            String runId = MAPPER.readTree(sample.getValue()).path("run").path("runId").asText();
            String event = consumed.get(runId);
            assertNotNull(event, "the sample " + sample.getKey() + " was not seen back from " + topic);
            JsonNode node = MAPPER.readTree(event);
            assertEquals(MAPPER.readTree(sample.getValue()).path("outputs").size(),
                    node.path("outputs").size());
            System.out.println("[CARDINALITY] verified " + sample.getKey() + " runId=" + runId);
        }
    }

    /** The sample directory of the repository, or null when the samples are not available. */
    private static Path sampleDirectory() {
        for (String candidate : new String[]{"../docs/samples", "docs/samples", "../../docs/samples"}) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Map<String, String> consume(
            String topic, List<String> runIds, LineageBridgeProperties properties) {
        Properties consumerProps = new Properties();
        consumerProps.putAll(OpenLineageKafkaBridge.producerProperties(properties));
        consumerProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        consumerProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG,
                "stweb-cardinality-sample-" + UUID.randomUUID());
        consumerProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Map<String, String> consumed = new LinkedHashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline && consumed.size() < runIds.size()) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(3))) {
                    if (record.value() == null) {
                        continue;
                    }
                    for (String runId : runIds) {
                        if (record.value().contains(runId)) {
                            consumed.put(runId, record.value());
                        }
                    }
                }
            }
        }
        return consumed;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
