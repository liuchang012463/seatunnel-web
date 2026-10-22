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
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Publishes rendered OpenLineage JSON to Kafka.
 *
 * <p>
 * The producer is created lazily on first send so a misconfigured bootstrap address costs
 * nothing until an event is actually due. Failures are best-effort logged, never thrown: the
 * Engine has already accepted the job and a bridge outage must not change its state. The
 * semantics handed to the asset side are at-least-once-as-possible, not guaranteed delivery.
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

    OpenLineageKafkaBridge(LineageBridgeProperties properties,
                           OpenLineageTopicProvisioner topicProvisioner,
                           @Qualifier("lineageEventExecutor") Executor retryExecutor) {
        this.properties = properties;
        this.topicProvisioner = topicProvisioner;
        this.retryExecutor = retryExecutor;
    }

    /**
     * Sends one event asynchronously with a null key (lineage volume is low, so no partition
     * affinity is needed) and logs an async failure at WARN without any retry or local cache.
     *
     * <p>
     * The only exception is a topic the broker does not know: the topic is created and the event
     * is sent one more time, because a missing topic is a recoverable state and not a delivery
     * problem.
     * </p>
     */
    void publish(String topic, String value) {
        if (!properties.getKafka().isEnabled()) {
            return;
        }
        Producer<String, String> kafkaProducer = producer();
        if (kafkaProducer == null) {
            return;
        }
        topicProvisioner.ensureTopic(topic);
        send(kafkaProducer, topic, value, true);
    }

    /**
     * Sends one event and reports the outcome. A missing topic is retried once, on the lineage
     * executor: the callback of the producer runs on the Kafka I/O thread, which must not be
     * blocked by a topic administration call.
     */
    private void send(
            Producer<String, String> kafkaProducer, String topic, String value, boolean retryAllowed) {
        try {
            kafkaProducer.send(new ProducerRecord<>(topic, null, value), (metadata, error) -> {
                if (error == null) {
                    return;
                }
                if (retryAllowed && isTopicMissing(error)) {
                    log.warn("OpenLineage topic {} is missing, creating it and sending again", topic);
                    topicProvisioner.invalidate(topic);
                    retryExecutor.execute(() -> {
                        topicProvisioner.ensureTopic(topic);
                        send(kafkaProducer, topic, value, false);
                    });
                    return;
                }
                log.warn("OpenLineage event send failed on topic {}: {}", topic, error.getMessage());
            });
        } catch (Exception error) {
            log.warn("Could not enqueue OpenLineage event on topic {}", topic, error);
        }
    }

    private static boolean isTopicMissing(Exception error) {
        return error instanceof UnknownTopicOrPartitionException;
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
        synchronized (this) {
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
        // Low-frequency small messages: reliability first, no batching delay.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
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
        if (StringUtils.isNotBlank(kafka.getSaslMechanism())) {
            props.put(SaslConfigs.SASL_MECHANISM, kafka.getSaslMechanism());
            props.put(SaslConfigs.SASL_JAAS_CONFIG,
                    buildJaas(kafka.getSaslMechanism(), kafka.getSaslUsername(), kafka.getSaslPassword()));
        }
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
        Producer<String, String> kafkaProducer = producer;
        if (kafkaProducer != null) {
            kafkaProducer.close(Duration.ofSeconds(5));
            log.info("OpenLineage Kafka bridge producer closed");
        }
    }
}
