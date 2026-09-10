package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaMetadataConnectorAdapterTest {

    private final KafkaMetadataConnectorAdapter adapter = new KafkaMetadataConnectorAdapter();

    @Test
    void buildsFixed11210KafkaMessagingServiceRequest() {
        DataSource dataSource = source(
                "{\"bootstrapServers\":\"kafka.example:9092\","
                        + "\"schemaRegistryUrl\":\"http://schema.example:8081\","
                        + "\"securityProtocol\":\"SASL_SSL\","
                        + "\"saslMechanism\":\"SCRAM_SHA_256\","
                        + "\"username\":\"kafka-user\",\"password\":\"secret\","
                        + "\"kafkaConfig\":{\"group.id\":\"om-ingest\"}}");

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_20");
        JsonNode pipeline = adapter.metadataPipelineRequest("st_ds_20_metadata", "uuid-1", "st_ds_20");

        assertEquals("Kafka", service.at("/serviceType").asText());
        assertEquals("Kafka", service.at("/connection/config/type").asText());
        assertEquals("kafka.example:9092", service.at("/connection/config/bootstrapServers").asText());
        assertEquals("http://schema.example:8081", service.at("/connection/config/schemaRegistryURL").asText());
        assertEquals("SASL_SSL", service.at("/connection/config/securityProtocol").asText());
        assertEquals("SCRAM-SHA-256", service.at("/connection/config/saslMechanism").asText());
        assertEquals("kafka-user", service.at("/connection/config/saslUsername").asText());
        assertEquals("secret", service.at("/connection/config/saslPassword").asText());
        assertEquals("om-ingest", service.at("/connection/config/consumerConfig/group.id").asText());
        assertEquals(true, service.at("/connection/config/supportsMetadataExtraction").asBoolean());
        assertEquals(MetadataServiceCategory.MESSAGING, adapter.serviceCategory());
        assertFalse(adapter.supportsProfiler());
        assertEquals("MessagingMetadata", pipeline.at("/sourceConfig/config/type").asText());
        assertEquals("messagingService", pipeline.at("/service/type").asText());
    }

    @Test
    void rejectsMissingSchemaRegistryUrl() {
        DataSource dataSource = source("{\"bootstrapServers\":\"kafka.example:9092\"}");

        MetadataIntegrationException error = assertThrows(
                MetadataIntegrationException.class,
                () -> adapter.serviceRequest(dataSource, "st_ds_21"));

        assertEquals(MetadataErrorCode.SOURCE_CONNECTION_ERROR, error.getErrorCode());
        assertTrue(error.getMessage().contains("schemaRegistryUrl"));
    }

    private static DataSource source(String connectionParams) {
        DataSource dataSource = new DataSource();
        dataSource.setId(20L);
        dataSource.setName("events");
        dataSource.setDbType(DbType.KAFKA);
        dataSource.setConnectionParams(connectionParams);
        return dataSource;
    }
}
