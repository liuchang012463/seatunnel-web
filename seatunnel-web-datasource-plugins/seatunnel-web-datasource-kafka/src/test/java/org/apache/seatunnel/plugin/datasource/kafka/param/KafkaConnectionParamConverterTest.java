package org.apache.seatunnel.plugin.datasource.kafka.param;

import org.apache.seatunnel.plugin.datasource.api.form.ReflectionFormGenerator;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FieldType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void parsesOptionalSchemaRegistryUrl() {
        KafkaConnectionParam param = converter.createConnectionParams(
                "{\"bootstrapServers\":\"localhost:9092\","
                        + "\"schemaRegistryUrl\":\"http://registry.example.com:8081\"}");

        assertEquals("http://registry.example.com:8081", param.getSchemaRegistryUrl());
        converter.checkDatasourceParam(param);
    }

    @Test
    void exposesSchemaRegistryUrlAsOptionalFormField() {
        List<FormFieldConfig> fields = ReflectionFormGenerator.generate(KafkaConnectionParam.class);

        FormFieldConfig field = fields.stream()
                .filter(item -> "schemaRegistryUrl".equals(item.getKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("Schema Registry 地址", field.getLabel());
        assertEquals("http://localhost:8081", field.getPlaceholder());
        assertEquals(FieldType.INPUT, field.getType());
        assertEquals(9, field.getOrder());
        assertNull(field.getRules());
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
