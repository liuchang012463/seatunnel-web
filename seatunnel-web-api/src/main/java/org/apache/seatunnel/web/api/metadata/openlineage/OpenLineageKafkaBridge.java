package org.apache.seatunnel.web.api.metadata.openlineage;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private volatile KafkaProducer<String, String> producer;

    private volatile boolean warnedBlankBootstrap;

    OpenLineageKafkaBridge(LineageBridgeProperties properties) {
        this.properties = properties;
    }

    /**
     * Sends one event asynchronously with a null key (lineage volume is low, so no partition
     * affinity is needed) and logs an async failure at WARN without any retry or local cache.
     */
    void publish(String topic, String value) {
        if (!properties.getKafka().isEnabled()) {
            return;
        }
        KafkaProducer<String, String> kafkaProducer = producer();
        if (kafkaProducer == null) {
            return;
        }
        try {
            kafkaProducer.send(new ProducerRecord<>(topic, null, value), (metadata, error) -> {
                if (error != null) {
                    log.warn("OpenLineage event send failed on topic {}: {}", topic, error.getMessage());
                }
            });
        } catch (Exception error) {
            log.warn("Could not enqueue OpenLineage event on topic {}", topic, error);
        }
    }

    /** Package-private state probe for unit tests. */
    boolean producerReady() {
        return producer != null;
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> existing = producer;
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
        if (StringUtils.isNotBlank(kafka.getSecurityProtocol())) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, kafka.getSecurityProtocol());
        }
        if (StringUtils.isNotBlank(kafka.getSaslMechanism())) {
            props.put(SaslConfigs.SASL_MECHANISM, kafka.getSaslMechanism());
            props.put(SaslConfigs.SASL_JAAS_CONFIG,
                    buildJaas(kafka.getSaslMechanism(), kafka.getSaslUsername(), kafka.getSaslPassword()));
        }
        return props;
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
        KafkaProducer<String, String> kafkaProducer = producer;
        if (kafkaProducer != null) {
            kafkaProducer.close(Duration.ofSeconds(5));
            log.info("OpenLineage Kafka bridge producer closed");
        }
    }
}
