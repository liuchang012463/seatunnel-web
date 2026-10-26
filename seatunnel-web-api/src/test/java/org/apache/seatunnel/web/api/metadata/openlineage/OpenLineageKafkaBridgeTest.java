package org.apache.seatunnel.web.api.metadata.openlineage;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

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
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        bridge.useProducer(acceptingProducer(sent));

        assertTrue(bridge.publish("meta_lineage_topic", "{}"));
        assertEquals(1, sent.size());
    }

    @Test
    void createsTheTopicAndSendsTheEventAgainWhenTheTopicIsMissing() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        OpenLineageKafkaBridge bridge = bridge(properties);
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        bridge.useProducer(topicMissingProducer(sent));

        // The missing topic is the only failure that is retried, and only once. The first
        // attempt still counts as not delivered, so the caller may publish the event again.
        assertFalse(bridge.publish("meta_lineage_topic", "{}"));
        assertEquals(2, sent.size());
    }

    @Test
    void doesNotRetryAnyOtherFailure() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        OpenLineageKafkaBridge bridge = bridge(properties);
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        bridge.useProducer(failingProducer(sent, new IllegalStateException("broker down")));

        assertFalse(bridge.publish("meta_lineage_topic", "{}"));
        assertEquals(1, sent.size());
    }

    /**
     * A broker that never acknowledges the event leaves the caller with an unconfirmed terminal,
     * so the publication has to be reported as failed.
     */
    @Test
    void anUnacknowledgedEventIsReportedAsNotDelivered() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        properties.getKafka().setSendTimeoutMs(100);
        OpenLineageKafkaBridge bridge = bridge(properties);
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        bridge.useProducer(neverAcknowledgedProducer(sent));

        assertFalse(bridge.publish("meta_lineage_topic", "{}"));
        assertEquals(1, sent.size());
    }

    /**
     * A SASL protocol without a password cannot authenticate. Creating the producer anyway makes
     * every send block for the metadata timeout and then fail, so the bridge refuses it.
     */
    @Test
    void aSaslProtocolWithoutAPasswordDoesNotCreateAProducer() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        properties.getKafka().setSecurityProtocol("SASL_PLAINTEXT");
        properties.getKafka().setSaslPassword("");
        OpenLineageKafkaBridge bridge = bridge(properties);

        assertFalse(bridge.publish("meta_lineage_topic", "{}"));
        assertFalse(bridge.producerReady());
    }

    @Test
    void aBlankPasswordOnAPlaintextProtocolStillPublishes() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        properties.getKafka().setSecurityProtocol("PLAINTEXT");
        properties.getKafka().setSaslPassword("");
        OpenLineageKafkaBridge bridge = bridge(properties);
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        bridge.useProducer(acceptingProducer(sent));

        assertTrue(bridge.publish("meta_lineage_topic", "{}"));
    }

    /** The record is keyed by the runId, so one run's events keep their partition order. */
    @Test
    void sendsTheEventKeyedByRun() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        OpenLineageKafkaBridge bridge = bridge(properties);
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        bridge.useProducer(acceptingProducer(sent));

        String runId = OpenLineageEventFactory.deriveRunId(100L, "engine-job-42").toString();
        assertTrue(bridge.publish("meta_lineage_topic", runId, "{}", true));

        assertEquals(1, sent.size());
        assertEquals(runId, sent.get(0).key());
    }

    /**
     * The delivery budget sits strictly below the send timeout: a send timeout then means the
     * record is dead, so a republished terminal can never be delivered twice.
     */
    @Test
    void theDeliveryTimeoutStaysBelowTheSendTimeout() {
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        properties.getKafka().setSendTimeoutMs(5000);

        Map<String, Object> props = OpenLineageKafkaBridge.producerProperties(properties);

        int deliveryTimeout = (int) props.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
        int requestTimeout = (int) props.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        assertTrue(deliveryTimeout < properties.getKafka().getSendTimeoutMs(),
                "delivery timeout must be below the send timeout");
        assertTrue(requestTimeout <= deliveryTimeout,
                "request timeout must fit into the delivery budget");
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

    /** A producer that records every send and acknowledges it. */
    private static Producer<String, String> acceptingProducer(List<ProducerRecord<String, String>> sent) {
        Producer<String, String> producer = mock(Producer.class);
        when(producer.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return acknowledged();
        });
        return producer;
    }

    /** A producer whose first send reports a missing topic and whose second one succeeds. */
    private static Producer<String, String> topicMissingProducer(
            List<ProducerRecord<String, String>> sent) {
        Producer<String, String> producer = mock(Producer.class);
        when(producer.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            if (sent.size() == 1) {
                throw new UnknownTopicOrPartitionException("no topic");
            }
            return acknowledged();
        });
        return producer;
    }

    /** A producer that always fails with the given error. */
    private static Producer<String, String> failingProducer(
            List<ProducerRecord<String, String>> sent, RuntimeException failure) {
        Producer<String, String> producer = mock(Producer.class);
        when(producer.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            throw failure;
        });
        return producer;
    }

    /** A producer that never acknowledges the record. */
    private static Producer<String, String> neverAcknowledgedProducer(
            List<ProducerRecord<String, String>> sent) {
        Producer<String, String> producer = mock(Producer.class);
        when(producer.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return new CompletableFuture<>();
        });
        return producer;
    }

    /** A future that is already completed. */
    private static Future<RecordMetadata> acknowledged() {
        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        future.complete(null);
        return future;
    }
}
