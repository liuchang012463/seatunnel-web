package org.apache.seatunnel.plugin.datasource.kafka.param;

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;

import java.util.LinkedHashMap;
import java.util.Map;

public final class KafkaClientProperties {

    private KafkaClientProperties() {}

    public static Map<String, Object> build(KafkaConnectionParam param) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, param.getBootstrapServers());
        properties.putAll(param.getKafkaConfig());
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, param.getSecurityProtocol().name());
        properties.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, param.getRequestTimeoutMs());
        properties.put(CommonClientConfigs.DEFAULT_API_TIMEOUT_MS_CONFIG, param.getRequestTimeoutMs());
        if (StringUtils.isNotBlank(param.getClientId())) {
            properties.put(CommonClientConfigs.CLIENT_ID_CONFIG, param.getClientId());
        }
        if (param.getSaslMechanism() != null) {
            properties.put(SaslConfigs.SASL_MECHANISM, param.getSaslMechanism().kafkaValue());
            properties.put(SaslConfigs.SASL_JAAS_CONFIG, buildJaas(param));
        }
        return properties;
    }

    static String buildJaas(KafkaConnectionParam param) {
        String loginModule = param.getSaslMechanism() == KafkaSaslMechanism.PLAIN
                ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        return loginModule + " required username=\"" + escapeJaas(param.getUsername())
                + "\" password=\"" + escapeJaas(param.getPassword()) + "\";";
    }

    static String escapeJaas(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
