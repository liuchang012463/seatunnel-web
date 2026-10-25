package org.apache.seatunnel.web.api.metadata.openlineage;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenLineageKafkaBridgeTest {

    private static LineageBridgeProperties properties(boolean enabled, String bootstrap) {
        LineageBridgeProperties properties = new LineageBridgeProperties();
        properties.getKafka().setEnabled(enabled);
        properties.getKafka().setBootstrapServers(bootstrap);
        return properties;
    }

    /** A bridge whose topic administration does nothing and whose retry runs inline. */
    private static OpenLineageKafkaBridge bridge(LineageBridgeProperties properties) {
        OpenLineageTopicProvisioner provisioner =
                new OpenLineageTopicProvisioner(properties, clientProperties ->
                        new OpenLineageTopicProvisioner.AdminOperations() {
                            @Override
                            public void createTopic(String topic, int timeoutMs) {
                            }

                            @Override
                            public void close() {
                            }
                        });
        return new OpenLineageKafkaBridge(properties, provisioner, Runnable::run);
    }

    @Test
    void disabledBridgePublishesWithoutCreatingAnyProducer() {
        OpenLineageKafkaBridge bridge = bridge(properties(false, "kafka:9092"));

        bridge.publish("meta_lineage_topic", "{}");

        assertFalse(bridge.producerReady());
    }

    @Test
    void enabledWithoutBootstrapLogsErrorAndSkipsInsteadOfThrowing() {
        OpenLineageKafkaBridge bridge = bridge(properties(true, ""));

        bridge.publish("meta_lineage_topic", "{}");

        assertFalse(bridge.producerReady());
    }

    @Test
    void sendsTheEventOnlyOnceWhenTheBrokerAcceptsIt() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        OpenLineageKafkaBridge bridge = bridge(properties);
        List<Callback> callbacks = new ArrayList<>();
        bridge.useProducer(recordingProducer(callbacks));

        bridge.publish("meta_lineage_topic", "{}");
        callbacks.get(0).onCompletion(null, null);

        assertEquals(1, callbacks.size());
    }

    @Test
    void createsTheTopicAndSendsTheEventAgainWhenTheTopicIsMissing() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        OpenLineageKafkaBridge bridge = bridge(properties);
        List<Callback> callbacks = new ArrayList<>();
        bridge.useProducer(recordingProducer(callbacks));

        bridge.publish("meta_lineage_topic", "{}");
        callbacks.get(0).onCompletion(null, new UnknownTopicOrPartitionException("no topic"));

        // The missing topic is the only failure that is retried, and only once.
        assertEquals(2, callbacks.size());
        callbacks.get(1).onCompletion(null, new UnknownTopicOrPartitionException("no topic"));
        assertEquals(2, callbacks.size());
    }

    @Test
    void doesNotRetryAnyOtherFailure() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        OpenLineageKafkaBridge bridge = bridge(properties);
        List<Callback> callbacks = new ArrayList<>();
        bridge.useProducer(recordingProducer(callbacks));

        bridge.publish("meta_lineage_topic", "{}");
        callbacks.get(0).onCompletion(null, new IllegalStateException("broker down"));

        assertEquals(1, callbacks.size());
    }

    @Test
    void producerPropertiesMatchTheDesignContract() {
        LineageBridgeProperties properties = properties(true, "kafka-1:9092,kafka-2:9092");
        LineageBridgeProperties.Kafka kafka = properties.getKafka();
        kafka.setSourceTopic("meta_lineage_topic");
        kafka.setSecurityProtocol("SASL_PLAINTEXT");
        kafka.setSaslMechanism("PLAIN");
        kafka.setSaslUsername("kafka-admin");
        kafka.setSaslPassword("test-password");
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
                .contains("username=\"kafka-admin\" password=\"test-password\""));
    }

    @Test
    void scramMechanismSwitchesTheLoginModule() {
        String jaas = OpenLineageKafkaBridge.buildJaas("SCRAM-SHA-256", "kafka-admin", "test-password");

        assertTrue(jaas.startsWith("org.apache.kafka.common.security.scram.ScramLoginModule"));
        assertTrue(jaas.contains("username=\"kafka-admin\""));
    }

    @Test
    void jaasEscapesQuotesAndBackslashes() {
        assertEquals("org.apache.kafka.common.security.plain.PlainLoginModule"
                        + " required username=\"a\\\"b\" password=\"c\\\\d\";",
                OpenLineageKafkaBridge.buildJaas("PLAIN", "a\"b", "c\\d"));
    }

    /** A producer that records the callback of every send. */
    private static Producer<String, String> recordingProducer(List<Callback> callbacks) {
        Producer<String, String> producer = mock(Producer.class);
        when(producer.send(any(), any())).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1));
            return null;
        });
        return producer;
    }
}
