package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaConnectivityTestJobDefinitionBuilderTest {

    @Test
    void shouldSupportKafkaDatasource() {
        assertTrue(new KafkaConnectivityTestJobDefinitionBuilder().supports(DbType.KAFKA));
    }

    @Test
    void shouldIncludeSchemaForJsonConnectivityProbe() {
        Config node = ConfigFactory.parseMap(
                KafkaConnectivityTestJobDefinitionBuilder.buildConnectivitySourceNode("codex_probe"));

        assertEquals("codex_probe", node.getString("topic"));
        assertEquals("json", node.getString("format"));
        assertEquals("string", node.getConfig("schema").getConfig("fields").getString("connectivity_probe"));
    }
}
