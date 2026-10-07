package org.apache.seatunnel.plugin.datasource.kafka.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KafkaHoconBuilderTest {

    private final KafkaHoconBuilder builder = new KafkaHoconBuilder();

    @Test
    void shouldBuildSourceUsingDefinedPrecedenceAndFilterReservedExtraParams() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("topic", "orders");
        node.put("consumerGroup", "orders-web");
        node.put("startMode", "specific_offsets");
        node.put("startModeOffsets", Map.of("0", 42));
        node.put("kafkaConfig", Map.of("compression.type", "lz4", "client.dns.lookup", "use_all_dns_ips"));
        node.put("extraParams", Map.of("topic", "must-not-win", "pluginName", "must-not-leak", "custom.option", "ok"));

        Config config = builder.buildSourceHocon(context(node));

        assertEquals("orders", config.getString("topic"));
        assertEquals("orders-web", config.getString("consumer.group"));
        assertEquals(42, config.getObject("\"start_mode.offsets\"").get("0").unwrapped());
        assertEquals("lz4", config.getString("kafka.config.\"compression.type\""));
        ConfigObject kafkaConfig = config.getObject("kafka.config");
        assertEquals("lz4", kafkaConfig.get("compression.type").unwrapped());
        assertTrue(kafkaConfig.containsKey("client.id"));
        assertTrue(kafkaConfig.containsKey("request.timeout.ms"));
        assertTrue(kafkaConfig.containsKey("security.protocol"));
        assertFalse(kafkaConfig.containsKey("compression"));
        assertFalse(kafkaConfig.containsKey("client"));
        assertEquals("ok", config.getString("custom.option"));
        assertFalse(config.hasPath("pluginName"));
        assertFalse(config.hasPath("pattern"));
        assertEquals("json", config.getString("format"));
    }

    @Test
    void shouldKeepSaslPropertiesAsFlatStringKeys() {
        String connection = "{\"bootstrapServers\":\"broker:9092\","
                + "\"securityProtocol\":\"SASL_PLAINTEXT\","
                + "\"saslMechanism\":\"PLAIN\","
                + "\"username\":\"user\",\"password\":\"test\","
                + "\"clientId\":\"web\",\"requestTimeoutMs\":8000,\"kafkaConfig\":{}}";
        Config config = builder.buildSourceHocon(context(Map.of("topic", "orders"), connection));
        ConfigObject kafkaConfig = config.getObject("kafka.config");

        assertTrue(kafkaConfig.containsKey("security.protocol"));
        assertTrue(kafkaConfig.containsKey("sasl.mechanism"));
        assertTrue(kafkaConfig.containsKey("sasl.jaas.config"));
        assertFalse(kafkaConfig.containsKey("security"));
        assertFalse(kafkaConfig.containsKey("sasl"));
    }

    @Test
    void shouldTreatExplicitFalseAsARegularTopicSubscription() {
        Config config = builder.buildSourceHocon(context(Map.of(
                "topic", "orders.eu",
                "pattern", false)));

        assertEquals("orders.eu", config.getString("topic"));
        assertFalse(config.hasPath("pattern"));
    }

    @Test
    void shouldValidateConditionalSourceFields() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSourceHocon(context(Map.of("topic", "orders", "startMode", "specific_offsets"))));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSourceHocon(context(Map.of("topic", "orders", "startMode", "timestamp"))));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSourceHocon(context(Map.of("topic", "orders", "pattern", "orders-.*"))));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSourceHocon(context(Map.of("topic", "orders", "format", "text"))));
    }

    @Test
    void shouldMigrateLegacyPatternRegexIntoTopic() {
        // 2.3.13 treats pattern as a boolean switch and expects the regex in
        // topic; legacy payloads carrying the regex in pattern are migrated.
        Config config = builder.buildSourceHocon(context(Map.of("pattern", "orders-.*")));

        assertEquals("orders-.*", config.getString("topic"));
        assertTrue(config.getBoolean("pattern"));
    }

    @Test
    void shouldValidateExactlyOnceAndPartitionExclusivity() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSinkHocon(context(Map.of("topic", "orders", "semantics", "EXACTLY_ONCE"))));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSinkHocon(context(Map.of(
                        "topic", "orders", "partition", 0,
                        "partitionKeyFields", Arrays.asList("tenant_id")))));

        Config config = builder.buildSinkHocon(context(Map.of(
                "topic", "orders-${tenant}", "semantics", "EXACTLY_ONCE",
                "transactionPrefix", "orders-prod")));
        assertEquals("orders-${tenant}", config.getString("topic"));
        assertEquals("orders-prod", config.getString("transaction_prefix"));
    }

    private HoconBuildContext context(Map<String, Object> node) {
        String connection = "{\"bootstrapServers\":\"broker:9092\","
                + "\"securityProtocol\":\"PLAINTEXT\","
                + "\"clientId\":\"web\","
                + "\"requestTimeoutMs\":10000,"
                + "\"kafkaConfig\":{\"compression.type\":\"gzip\"}}";
        return context(node, connection);
    }

    private HoconBuildContext context(Map<String, Object> node, String connection) {
        return HoconBuildContext.builder()
                .connectionParam(connection)
                .connectionConfig(ConfigFactory.parseString(connection))
                .nodeConfig(ConfigFactory.parseMap(node))
                .build();
    }
}
