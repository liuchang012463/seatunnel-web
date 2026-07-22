package org.apache.seatunnel.plugin.datasource.kafka.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
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
        assertEquals("ok", config.getString("custom.option"));
        assertFalse(config.hasPath("pluginName"));
        assertEquals("json", config.getString("format"));
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
        return HoconBuildContext.builder()
                .connectionParam(connection)
                .connectionConfig(ConfigFactory.parseString(connection))
                .nodeConfig(ConfigFactory.parseMap(node))
                .build();
    }
}
