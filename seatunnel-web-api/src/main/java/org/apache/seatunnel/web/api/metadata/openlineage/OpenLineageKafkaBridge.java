package org.apache.seatunnel.web.api.metadata.openlineage;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes rendered OpenLineage JSON to Kafka.
 *
 * <p>
 * The producer is created lazily on first send so a misconfigured bootstrap address costs
 * nothing until an event is actually due. Failures are best-effort logged, never thrown: the
 * Engine has already accepted the job and a bridge outage must not change its state.
 * </p>
 *
 * <p>
 * {@link #publish(String, String)} reports the delivery outcome, so the caller can decide
 * whether a terminal event may be forgotten. A record that was buffered but never acknowledged
 * counts as not delivered.
 * </p>
 */
@Service
@ConditionalOnProperty(prefix = "lineage-bridge.kafka", name = "enabled", havingValue = "true")
@Slf4j
public class OpenLineageKafkaBridge {

    private final LineageBridgeProperties properties;

    private final OpenLineageTopicProvisioner topicProvisioner;

    private final Executor retryExecutor;

    private volatile Producer<String, String> producer;

    private volatile boolean warnedBlankBootstrap;

    private volatile boolean warnedBlankPassword;

    private volatile boolean closed;

    OpenLineageKafkaBridge(LineageBridgeProperties properties,
                           OpenLineageTopicProvisioner topicProvisioner,
                           @Qualifier("lineageEventExecutor") Executor retryExecutor) {
        this.properties = properties;
        this.topicProvisioner = topicProvisioner;
        this.retryExecutor = retryExecutor;
    }

    /**
     * Sends one event and reports whether the broker acknowledged it.
     *
     * <p>
     * The record is keyed by the runId, so every event of one run lands on one partition in send
     * order: a consumer never sees a terminal before the START of the same run.
     * </p>
     *
     * <p>
     * The outcome is the delivery outcome, not the enqueue outcome: the caller decides whether a
     * terminal event may be forgotten, so a record that was buffered but never acknowledged has to
     * be reported as a failure. The delivery timeout of the producer sits strictly below the send
     * timeout, so a send timeout means the record is dead, not still in flight — publishing the
     * terminal again cannot produce a second delivered terminal.
     * </p>
     *
     * <p>
     * The only recoverable send failure is a topic the broker does not know: the topic is created
     * and the event is sent one more time, because a missing topic is a recoverable state and not
     * a delivery problem.
     * </p>
     *
     * @return true when the broker acknowledged the event, false when the bridge is disabled,
     *     unconfigured or the delivery failed
     */
    boolean publish(String topic, String key, String value, boolean retryOnMissingTopic) {
        if (!properties.getKafka().isEnabled() || closed) {
            return false;
        }
        Producer<String, String> kafkaProducer = producer();
        if (kafkaProducer == null) {
            return false;
        }
        topicProvisioner.ensureTopic(topic);
        return send(kafkaProducer, topic, key, value, retryOnMissingTopic);
    }

    /**
     * Sends one event with a null key (lineage volume is low, so no partition affinity is needed)
     * and reports whether the broker acknowledged it.
     *
     * @return true when the broker acknowledged the event, false when the bridge is disabled,
     *     unconfigured or the delivery failed
     */
    boolean publish(String topic, String value) {
        return publish(topic, null, value, true);
    }

    /**
     * Sends one event and reports whether the broker acknowledged it.
     *
     * @param retryOnMissingTopic whether the bridge creates a missing topic and sends the event
     *     again. A terminal event must not be sent twice: the caller publishes it again when
     *     this method reports a failure.
     */
    boolean publish(String topic, String value, boolean retryOnMissingTopic) {
        return publish(topic, null, value, retryOnMissingTopic);
    }

    /**
     * Sends one event and waits for the broker acknowledgement, bounded by the configured send
     * timeout. A missing topic is retried once, on the lineage executor: the retry must not block
     * the caller, and the caller learns the delivery failed so it can publish the event again.
     */
    private boolean send(
            Producer<String, String> kafkaProducer,
            String topic,
            String key,
            String value,
            boolean retryAllowed) {
        try {
            kafkaProducer.send(new ProducerRecord<>(topic, key, value))
                    .get(properties.getKafka().getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while sending the OpenLineage event to topic {}", topic);
            return false;
        } catch (TimeoutException timeout) {
            log.warn("The OpenLineage event to topic {} was not acknowledged within {} ms",
                    topic, properties.getKafka().getSendTimeoutMs());
            return false;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (retryAllowed && cause instanceof UnknownTopicOrPartitionException) {
                log.warn("OpenLineage topic {} is missing, creating it and sending again", topic);
                topicProvisioner.invalidate(topic);
                retryExecutor.execute(() -> {
                    topicProvisioner.ensureTopic(topic);
                    send(kafkaProducer, topic, key, value, false);
                });
                return false;
            }
            log.warn("OpenLineage event send failed on topic {}: {}", topic, cause.getMessage());
            return false;
        } catch (Exception error) {
            // The producer rejected the record itself, e.g. because the topic is unknown.
            if (retryAllowed && error instanceof UnknownTopicOrPartitionException) {
                log.warn("OpenLineage topic {} is missing, creating it and sending again", topic);
                topicProvisioner.invalidate(topic);
                retryExecutor.execute(() -> {
                    topicProvisioner.ensureTopic(topic);
                    send(kafkaProducer, topic, key, value, false);
                });
                return false;
            }
            log.warn("Could not enqueue OpenLineage event on topic {}: {}", topic, error.getMessage());
            return false;
        }
    }

    /** Package-private state probe for unit tests. */
    boolean producerReady() {
        return producer != null;
    }

    /** Package-private seam for the unit tests: a producer that is not a real broker client. */
    void useProducer(Producer<String, String> producer) {
        this.producer = producer;
    }

    private Producer<String, String> producer() {
        if (closed) {
            return null;
        }
        Producer<String, String> existing = producer;
        if (existing != null) {
            return existing;
        }
        if (StringUtils.isBlank(properties.getKafka().getBootstrapServers())) {
            if (!warnedBlankBootstrap) {
                warnedBlankBootstrap = true;
                log.error("lineage-bridge.kafka.bootstrap-servers is blank while enabled=true; "
                        + "OpenLineage events are dropped");
            }
            return null;
        }
        if (requiresSaslPassword(properties.getKafka().getSecurityProtocol(),
                properties.getKafka().getSaslMechanism())
                && StringUtils.isBlank(properties.getKafka().getSaslPassword())) {
            // A SASL mechanism that uses a password cannot authenticate without one: the
            // producer would be created, every send would block for the metadata timeout and
            // then fail. Refusing the producer keeps the failure fast and visible.
            if (!warnedBlankPassword) {
                warnedBlankPassword = true;
                log.error("lineage-bridge.kafka.security-protocol={} with sasl-mechanism={} requires a "
                                + "lineage-bridge.kafka.sasl-password while enabled=true; "
                                + "OpenLineage events are dropped",
                        properties.getKafka().getSecurityProtocol(),
                        properties.getKafka().getSaslMechanism());
            }
            return null;
        }
        synchronized (this) {
            if (closed) {
                return null;
            }
            if (producer == null) {
                LineageBridgeProperties.Kafka kafka = properties.getKafka();
                producer = new KafkaProducer<>(producerProperties(properties));
                log.info("OpenLineage Kafka bridge producer initialized, bootstrapServers={}, topic={}, "
                                + "securityProtocol={}, saslMechanism={}",
                        kafka.getBootstrapServers(), kafka.getSourceTopic(),
                        kafka.getSecurityProtocol(), kafka.getSaslMechanism());
            }
            return producer;
        }
    }

    static Map<String, Object> producerProperties(LineageBridgeProperties properties) {
        LineageBridgeProperties.Kafka kafka = properties.getKafka();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Low-frequency small messages: reliability first, no batching delay. The metadata wait is
        // bounded so an unreachable broker cannot pin a lineage worker for the client default.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, kafka.getMaxBlockMs());
        // The delivery budget stays strictly below the send timeout: when the bridge reports a
        // send timeout, the record is already dead in the producer, so a caller that publishes a
        // terminal again can never put a second delivered terminal on the wire.
        int deliveryTimeoutMs = Math.max(100, kafka.getSendTimeoutMs() - 500);
        if (deliveryTimeoutMs >= kafka.getSendTimeoutMs()) {
            deliveryTimeoutMs = Math.max(100, kafka.getSendTimeoutMs() / 2);
        }
        int requestTimeoutMs = Math.min(deliveryTimeoutMs, Math.max(100, deliveryTimeoutMs * 4 / 5));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        if (StringUtils.isNotBlank(kafka.getClientId())) {
            props.put(ProducerConfig.CLIENT_ID_CONFIG, kafka.getClientId());
        }
        addSecurityProperties(props, kafka);
        return props;
    }

    /**
     * Client properties of the administration client that creates the topic. They share the
     * connection settings of the producer, so the topic is created on the same cluster with the
     * same credentials.
     */
    static Map<String, Object> adminProperties(LineageBridgeProperties properties) {
        LineageBridgeProperties.Kafka kafka = properties.getKafka();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, kafka.getAdminTimeoutMs());
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, kafka.getAdminTimeoutMs());
        if (StringUtils.isNotBlank(kafka.getClientId())) {
            props.put(AdminClientConfig.CLIENT_ID_CONFIG, kafka.getClientId() + "-admin");
        }
        addSecurityProperties(props, kafka);
        return props;
    }

    /** The connection settings both clients share. */
    private static void addSecurityProperties(
            Map<String, Object> props, LineageBridgeProperties.Kafka kafka) {
        if (StringUtils.isNotBlank(kafka.getSecurityProtocol())) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, kafka.getSecurityProtocol());
        }
        if (!usesSasl(kafka.getSecurityProtocol()) || StringUtils.isBlank(kafka.getSaslMechanism())) {
            return;
        }
        props.put(SaslConfigs.SASL_MECHANISM, kafka.getSaslMechanism());
        String mechanism = kafka.getSaslMechanism().toUpperCase(Locale.ROOT);
        if ("PLAIN".equals(mechanism) || mechanism.startsWith("SCRAM")) {
            props.put(SaslConfigs.SASL_JAAS_CONFIG,
                    buildJaas(kafka.getSaslMechanism(), kafka.getSaslUsername(), kafka.getSaslPassword()));
        }
    }

    /**
     * True when the mechanism authenticates with a password. OAUTHBEARER and GSSAPI use a token
     * or the host credentials, so they need no password.
     */
    private static boolean requiresSaslPassword(String securityProtocol, String saslMechanism) {
        if (!usesSasl(securityProtocol)) {
            return false;
        }
        String mechanism = StringUtils.trimToNull(saslMechanism);
        if (mechanism == null) {
            return true;
        }
        String upper = mechanism.toUpperCase(Locale.ROOT);
        return "PLAIN".equals(upper) || upper.startsWith("SCRAM");
    }

    private static boolean usesSasl(String securityProtocol) {
        return StringUtils.isNotBlank(securityProtocol)
                && securityProtocol.toUpperCase(Locale.ROOT).contains("SASL");
    }

    /** JAAS string for PLAIN / SCRAM mechanisms, mirroring the Kafka datasource connector. */
    static String buildJaas(String mechanism, String username, String password) {
        String loginModule = "PLAIN".equalsIgnoreCase(mechanism)
                ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        return loginModule + " required username=\"" + escapeJaas(username)
                + "\" password=\"" + escapeJaas(password) + "\";";
    }

    static String escapeJaas(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @PreDestroy
    void close() {
        Producer<String, String> kafkaProducer;
        synchronized (this) {
            closed = true;
            kafkaProducer = producer;
            producer = null;
        }
        if (kafkaProducer != null) {
            kafkaProducer.close(Duration.ofSeconds(5));
            log.info("OpenLineage Kafka bridge producer closed");
        }
    }
}
