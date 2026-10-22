package org.apache.seatunnel.web.api.metadata.openlineage;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLineageTopicProvisionerTest {

    private static LineageBridgeProperties properties(boolean autoCreate, String bootstrap) {
        LineageBridgeProperties properties = new LineageBridgeProperties();
        properties.getKafka().setEnabled(true);
        properties.getKafka().setBootstrapServers(bootstrap);
        properties.getKafka().setAutoCreateTopic(autoCreate);
        return properties;
    }

    /** A fake administration client that records the topics it was asked to create. */
    private static OpenLineageTopicProvisioner provisioner(
            LineageBridgeProperties properties, List<String> created, RuntimeException failure) {
        return new OpenLineageTopicProvisioner(properties, clientProperties ->
                new OpenLineageTopicProvisioner.AdminOperations() {
                    @Override
                    public void createTopic(String topic, int timeoutMs) {
                        created.add(topic);
                        if (failure != null) {
                            throw failure;
                        }
                    }

                    @Override
                    public void close() {
                    }
                });
    }

    @Test
    void createsTheTopicWhenItIsMissing() {
        List<String> created = new ArrayList<>();
        OpenLineageTopicProvisioner provisioner = provisioner(properties(true, "kafka:9092"), created, null);

        provisioner.ensureTopic("meta_lineage_topic");

        assertEquals(List.of("meta_lineage_topic"), created);
    }

    @Test
    void runsTheStartupCheckOnTheGivenExecutor() {
        List<String> created = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        LineageBridgeProperties properties = properties(true, "kafka:9092");
        properties.getKafka().setSourceTopic("meta_lineage_topic");
        OpenLineageTopicProvisioner provisioner = new OpenLineageTopicProvisioner(
                properties,
                clientProperties -> new OpenLineageTopicProvisioner.AdminOperations() {
                    @Override
                    public void createTopic(String topic, int timeoutMs) {
                        created.add(topic);
                    }

                    @Override
                    public void close() {
                    }
                },
                tasks::add);

        provisioner.ensureConfiguredTopic();

        // The check is dispatched, not run inline: an unreachable broker must not delay the
        // start of the application.
        assertTrue(created.isEmpty());
        assertEquals(1, tasks.size());
        tasks.get(0).run();
        assertEquals(List.of("meta_lineage_topic"), created);
    }

    @Test
    void createsTheTopicOnlyOnce() {        List<String> created = new ArrayList<>();
        OpenLineageTopicProvisioner provisioner = provisioner(properties(true, "kafka:9092"), created, null);

        provisioner.ensureTopic("meta_lineage_topic");
        provisioner.ensureTopic("meta_lineage_topic");

        assertEquals(1, created.size());
    }

    @Test
    void createsTheTopicAgainAfterItWasInvalidated() {
        List<String> created = new ArrayList<>();
        OpenLineageTopicProvisioner provisioner = provisioner(properties(true, "kafka:9092"), created, null);

        provisioner.ensureTopic("meta_lineage_topic");
        provisioner.invalidate("meta_lineage_topic");
        provisioner.ensureTopic("meta_lineage_topic");

        assertEquals(2, created.size());
    }

    @Test
    void createsNothingWhenAutoCreationIsOff() {
        List<String> created = new ArrayList<>();
        OpenLineageTopicProvisioner provisioner = provisioner(properties(false, "kafka:9092"), created, null);

        provisioner.ensureTopic("meta_lineage_topic");

        assertTrue(created.isEmpty());
    }

    @Test
    void createsNothingWithoutABrokerAddress() {
        List<String> created = new ArrayList<>();
        OpenLineageTopicProvisioner provisioner = provisioner(properties(true, ""), created, null);

        provisioner.ensureTopic("meta_lineage_topic");

        assertTrue(created.isEmpty());
    }

    @Test
    void survivesAFailingCreationAndDoesNotRetryItBeforeTheCooldown() {
        List<String> created = new ArrayList<>();
        OpenLineageTopicProvisioner provisioner = provisioner(
                properties(true, "kafka:9092"), created, new IllegalStateException("no permission"));

        provisioner.ensureTopic("meta_lineage_topic");
        provisioner.ensureTopic("meta_lineage_topic");

        // The failure never leaves the bridge, and it is not hammered on every event.
        assertEquals(1, created.size());
    }

    @Test
    void letsTheClusterDecidePartitionsAndReplication() {
        NewTopic topic = OpenLineageTopicProvisioner.newTopic("meta_lineage_topic");

        assertEquals("meta_lineage_topic", topic.name());
        assertEquals(-1, topic.numPartitions());
        assertEquals((short) -1, topic.replicationFactor());
    }

    @Test
    void usesTheSameConnectionSettingsAsTheProducer() {
        LineageBridgeProperties properties = properties(true, "kafka-1:9092");
        properties.getKafka().setSecurityProtocol("SASL_PLAINTEXT");
        properties.getKafka().setSaslMechanism("PLAIN");
        properties.getKafka().setSaslUsername("kafka-admin");
        properties.getKafka().setSaslPassword("Kafka@2024Secure!");
        properties.getKafka().setClientId("seatunnel-web-lineage-bridge");

        Map<String, Object> props = OpenLineageKafkaBridge.adminProperties(properties);

        assertEquals("kafka-1:9092", props.get("bootstrap.servers"));
        assertEquals("SASL_PLAINTEXT", props.get("security.protocol"));
        assertEquals("PLAIN", props.get("sasl.mechanism"));
        assertEquals("seatunnel-web-lineage-bridge-admin", props.get("client.id"));
        assertTrue(((String) props.get("sasl.jaas.config")).contains("username=\"kafka-admin\""));
    }
}
