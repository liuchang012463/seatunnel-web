package org.apache.seatunnel.web.api.metadata.openlineage;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLineageKafkaBridgeTest {

    private static LineageBridgeProperties properties(boolean enabled, String bootstrap) {
        LineageBridgeProperties properties = new LineageBridgeProperties();
        properties.getKafka().setEnabled(enabled);
        properties.getKafka().setBootstrapServers(bootstrap);
        return properties;
    }

    @Test
    void disabledBridgePublishesWithoutCreatingAnyProducer() {
        OpenLineageKafkaBridge bridge = new OpenLineageKafkaBridge(properties(false, "kafka:9092"));

        bridge.publish("meta_lineage_topic", "{}");

        assertFalse(bridge.producerReady());
    }

    @Test
    void enabledWithoutBootstrapLogsErrorAndSkipsInsteadOfThrowing() {
        OpenLineageKafkaBridge bridge = new OpenLineageKafkaBridge(properties(true, ""));

        bridge.publish("meta_lineage_topic", "{}");

        assertFalse(bridge.producerReady());
    }

    @Test
    void producerPropertiesMatchTheDesignContract() {
        LineageBridgeProperties properties = properties(true, "kafka-1:9092,kafka-2:9092");
        LineageBridgeProperties.Kafka kafka = properties.getKafka();
        kafka.setSourceTopic("meta_lineage_topic");
        kafka.setSecurityProtocol("SASL_PLAINTEXT");
        kafka.setSaslMechanism("PLAIN");
        kafka.setSaslUsername("kafka-admin");
        kafka.setSaslPassword("Kafka@2024Secure!");
        kafka.setClientId("seatunnel-web-lineage-bridge");

        Map<String, Object> props = OpenLineageKafkaBridge.producerProperties(properties);

        assertEquals("kafka-1:9092,kafka-2:9092", props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("org.apache.kafka.common.serialization.StringSerializer",
                props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals("org.apache.kafka.common.serialization.StringSerializer",
                props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals("all", props.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(5, props.get(ProducerConfig.RETRIES_CONFIG));
        assertEquals("seatunnel-web-lineage-bridge", props.get(ProducerConfig.CLIENT_ID_CONFIG));
        assertEquals("SASL_PLAINTEXT", props.get("security.protocol"));
        assertEquals("PLAIN", props.get("sasl.mechanism"));
        assertTrue(((String) props.get("sasl.jaas.config")).startsWith(
                "org.apache.kafka.common.security.plain.PlainLoginModule"));
        assertTrue(((String) props.get("sasl.jaas.config"))
                .contains("username=\"kafka-admin\" password=\"Kafka@2024Secure!\""));
    }

    @Test
    void scramMechanismSwitchesTheLoginModule() {
        String jaas = OpenLineageKafkaBridge.buildJaas("SCRAM-SHA-256", "kafka-admin", "Kafka@2024Secure!");

        assertTrue(jaas.startsWith("org.apache.kafka.common.security.scram.ScramLoginModule"));
        assertTrue(jaas.contains("username=\"kafka-admin\""));
    }

    @Test
    void jaasEscapesQuotesAndBackslashes() {
        assertEquals("org.apache.kafka.common.security.plain.PlainLoginModule"
                        + " required username=\"a\\\"b\" password=\"c\\\\d\";",
                OpenLineageKafkaBridge.buildJaas("PLAIN", "a\"b", "c\\d"));
    }
}
