package org.apache.seatunnel.plugin.datasource.kafka.param;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaConnectionParamConverterTest {

    private final KafkaConnectionParamConverter converter =
            new KafkaConnectionParamConverter();

    @Test
    void parsesEmptyKafkaConfigStringAsEmptyMap() {
        String json = "{\"bootstrapServers\":\"localhost:9092\","
                + "\"kafkaConfig\":\"\"}";

        KafkaConnectionParam param = converter.createConnectionParams(json);

        assertNotNull(param);
        assertNotNull(param.getKafkaConfig());
        assertTrue(param.getKafkaConfig().isEmpty());
        assertEquals(DbType.KAFKA, param.getDbType());
    }

    @Test
    void parsesEmptyKafkaConfigStringWithSpacesAsEmptyMap() {
        String json = "{\"bootstrapServers\":\"localhost:9092\","
                + "\"kafkaConfig\"  :  \"\"}";

        KafkaConnectionParam param = converter.createConnectionParams(json);

        assertNotNull(param);
        assertNotNull(param.getKafkaConfig());
        assertTrue(param.getKafkaConfig().isEmpty());
    }

    @Test
    void omittedKafkaConfigDefaultsToEmptyMap() {
        String json = "{\"bootstrapServers\":\"localhost:9092\"}";

        KafkaConnectionParam param = converter.createConnectionParams(json);

        assertNotNull(param);
        assertNotNull(param.getKafkaConfig());
        assertTrue(param.getKafkaConfig().isEmpty());
        assertEquals(DbType.KAFKA, param.getDbType());
    }

    @Test
    void parsesValidKafkaConfigRoundTrip() {
        String json = "{\"bootstrapServers\":\"localhost:9092\","
                + "\"kafkaConfig\":{\"compression.type\":\"lz4\","
                + "\"acks\":\"all\"}}";

        KafkaConnectionParam param = converter.createConnectionParams(json);

        assertNotNull(param);
        Map<String, String> kafkaConfig = param.getKafkaConfig();
        assertNotNull(kafkaConfig);
        assertEquals(2, kafkaConfig.size());
        assertEquals("lz4", kafkaConfig.get("compression.type"));
        assertEquals("all", kafkaConfig.get("acks"));
    }

    @Test
    void nullConnectionJsonThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> converter.createConnectionParams(null));
    }

    @Test
    void nullConnectionParamInCheckThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> converter.checkDatasourceParam(null));
    }

    @Test
    void rejectsMissingBootstrapServers() {
        KafkaConnectionParam param = new KafkaConnectionParam();
        param.setBootstrapServers("");

        assertThrows(
                IllegalArgumentException.class,
                () -> converter.checkDatasourceParam(param));
    }

    @Test
    void checkPassesForValidMinimalParam() {
        KafkaConnectionParam param = converter.createConnectionParams(
                "{\"bootstrapServers\":\"localhost:9092\"}");

        converter.checkDatasourceParam(param);
    }

    @Test
    void doesNotMutateStringValuesContainingKafkaConfig() {
        String json = "{\"bootstrapServers\":\"kafkaConfig is a name not a field\","
                + "\"kafkaConfig\":\"\"}";

        KafkaConnectionParam param = converter.createConnectionParams(json);

        assertNotNull(param);
        assertNotNull(param.getKafkaConfig());
        assertTrue(param.getKafkaConfig().isEmpty());
        assertFalse(param.getBootstrapServers().isEmpty());
    }
}