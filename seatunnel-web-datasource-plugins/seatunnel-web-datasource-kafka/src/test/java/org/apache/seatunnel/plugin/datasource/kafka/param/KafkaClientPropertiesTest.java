package org.apache.seatunnel.plugin.datasource.kafka.param;

import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KafkaClientPropertiesTest {

    @Test
    void shouldEscapeJaasAndKeepSecretsOutOfToString() {
        KafkaConnectionParam param = new KafkaConnectionParam();
        param.setBootstrapServers("localhost:9092");
        param.setSecurityProtocol(KafkaSecurityProtocol.SASL_SSL);
        param.setSaslMechanism(KafkaSaslMechanism.SCRAM_SHA_512);
        param.setUsername("user\"name");
        param.setPassword("p\\a\"ss");

        Map<String, Object> properties = KafkaClientProperties.build(param);
        String jaas = String.valueOf(properties.get(SaslConfigs.SASL_JAAS_CONFIG));

        assertTrue(jaas.contains("username=\"user\\\"name\""));
        assertTrue(jaas.contains("password=\"p\\\\a\\\"ss\""));
        assertEquals("SCRAM-SHA-512", properties.get(SaslConfigs.SASL_MECHANISM));
        assertFalse(param.toString().contains("p\\a"));
        assertFalse(param.toString().contains("user\"name"));
    }

    @Test
    void structuredSecurityFieldsShouldOverrideAdvancedConfig() {
        KafkaConnectionParam param = new KafkaConnectionParam();
        param.setBootstrapServers("broker:9092");
        param.setSecurityProtocol(KafkaSecurityProtocol.SSL);
        param.getKafkaConfig().put("security.protocol", "PLAINTEXT");

        assertEquals("SSL", KafkaClientProperties.build(param).get("security.protocol"));
    }
}
