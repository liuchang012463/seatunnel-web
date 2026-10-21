package org.apache.seatunnel.web.api.metadata.openlineage;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Creates the lineage topic when the broker does not have it yet.
 *
 * <p>
 * A broker with automatic topic creation turned off rejects every send to an unknown topic, so
 * the bridge would lose its events silently. The provisioner creates the topic instead, and it
 * follows the cluster: the partitions and the replication factor are left to the broker defaults
 * ({@code num.partitions} and {@code default.replication.factor}).
 * </p>
 *
 * <p>
 * Every failure is logged and never thrown: the bridge must not change the state of a job the
 * Engine has already accepted. A failed attempt is not repeated before a cooldown, so a broker
 * outage cannot turn every lineage event into a slow administration call.
 * </p>
 */
@Slf4j
public class OpenLineageTopicProvisioner {

    /** A failed creation is not retried before this cooldown. */
    private static final long FAILURE_COOLDOWN_MS = 60_000;

    private final LineageBridgeProperties properties;

    private final AdminOperationsFactory operationsFactory;

    /** Topics that are known to exist. */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    /** Topics whose last creation attempt failed, with the time of that attempt. */
    private final Map<String, Long> failures = new ConcurrentHashMap<>();

    public OpenLineageTopicProvisioner(LineageBridgeProperties properties) {
        this(properties, clientProperties -> new KafkaAdminOperations(AdminClient.create(clientProperties)));
    }

    OpenLineageTopicProvisioner(
            LineageBridgeProperties properties, AdminOperationsFactory operationsFactory) {
        this.properties = properties;
        this.operationsFactory = operationsFactory;
    }

    /**
     * Creates the topic when it is missing. A topic that already exists is left alone, and a
     * failure is logged without being thrown.
     */
    public void ensureTopic(String topic) {
        LineageBridgeProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isAutoCreateTopic()) {
            return;
        }
        String name = StringUtils.trimToNull(topic);
        if (name == null || ensured.contains(name)) {
            return;
        }
        if (StringUtils.isBlank(kafka.getBootstrapServers())) {
            return;
        }
        Long failedAt = failures.get(name);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) {
            return;
        }
        try (AdminOperations admin =
                operationsFactory.create(OpenLineageKafkaBridge.adminProperties(properties))) {
            admin.createTopic(name, kafka.getAdminTimeoutMs());
            failures.remove(name);
            ensured.add(name);
            log.info("OpenLineage lineage topic is available: {}", name);
        } catch (Exception error) {
            failures.put(name, System.currentTimeMillis());
            log.warn("Could not create the OpenLineage lineage topic {}: {}", name, error.getMessage());
        }
    }

    /**
     * Forgets what is known about a topic, so the next call tries to create it again. Used when
     * the broker reports that the topic is gone.
     */
    public void invalidate(String topic) {
        String name = StringUtils.trimToNull(topic);
        if (name == null) {
            return;
        }
        ensured.remove(name);
        failures.remove(name);
    }

    /**
     * Creates the topic the bridge publishes to, once the application is up. The topic is then
     * there before the first job of the day is submitted.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureConfiguredTopic() {
        ensureTopic(properties.getKafka().getSourceTopic());
    }

    @FunctionalInterface
    interface AdminOperationsFactory {
        AdminOperations create(Map<String, Object> properties);
    }

    interface AdminOperations extends AutoCloseable {
        void createTopic(String topic, int timeoutMs) throws Exception;

        @Override
        void close();
    }

    private static final class KafkaAdminOperations implements AdminOperations {

        private final AdminClient adminClient;

        private KafkaAdminOperations(AdminClient adminClient) {
            this.adminClient = adminClient;
        }

        @Override
        public void createTopic(String topic, int timeoutMs) throws Exception {
            try {
                adminClient.createTopics(List.of(newTopic(topic))).all().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof TopicExistsException) {
                    return;
                }
                throw error;
            }
        }

        @Override
        public void close() {
            adminClient.close();
        }
    }

    /**
     * The topic the broker creates with its own partition and replication defaults: an empty
     * partition count and replication factor ask the cluster to decide.
     */
    static NewTopic newTopic(String topic) {
        return new NewTopic(topic, Optional.empty(), Optional.empty());
    }
}
