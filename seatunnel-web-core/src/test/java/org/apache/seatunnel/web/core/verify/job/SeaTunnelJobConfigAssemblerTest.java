package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SeaTunnelJobConfigAssemblerTest {

    @Test
    void shouldPreserveLiteralDottedKeysWhenWrappingPluginConfig() {
        ConfigObject kafkaConfig = ConfigFactory.empty().root()
                .withValue("client.id", ConfigValueFactory.fromAnyRef("seatunnel-web"))
                .withValue("security.protocol", ConfigValueFactory.fromAnyRef("SASL_PLAINTEXT"));
        Config source = ConfigFactory.empty().withValue("kafka.config", kafkaConfig);
        String hocon = new SeaTunnelJobConfigAssembler().assemble(
                ConfigFactory.parseString("job.mode = BATCH"),
                "Kafka",
                source,
                "Console",
                ConfigFactory.empty());

        Config parsed = ConfigFactory.parseString(hocon);
        ConfigObject parsedKafkaConfig = parsed.getConfig("source.Kafka").getObject("kafka.config");

        assertEquals("seatunnel-web", parsedKafkaConfig.get("client.id").unwrapped());
        assertEquals("SASL_PLAINTEXT", parsedKafkaConfig.get("security.protocol").unwrapped());
        assertFalse(parsedKafkaConfig.containsKey("client"));
        assertFalse(parsedKafkaConfig.containsKey("security"));
    }
}
