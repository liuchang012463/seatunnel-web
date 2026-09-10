package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpMetadataConnectorAdapterTest {

    private final HttpMetadataConnectorAdapter adapter = new HttpMetadataConnectorAdapter();

    @BeforeAll
    static void configureMasterKey() {
        System.setProperty("seatunnel.web.datasource.master-key", "test-master-key");
    }

    @Test
    void buildsFixed11210RestApiServiceRequest() {
        DataSource dataSource = source(41L, """
                {
                  "baseUrl": "https://api.example.com",
                  "openApiSpecUrl": "https://api.example.com/openapi.json",
                  "authenticationType": "BEARER",
                  "bearerToken": "%s"
                }
                """.formatted(PasswordUtils.encodePassword("token-value")));

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_41");
        JsonNode pipeline = adapter.metadataPipelineRequest("st_ds_41_metadata", "uuid-1", "st_ds_41");

        assertEquals("Rest", service.at("/serviceType").asText());
        assertEquals("Rest", service.at("/connection/config/type").asText());
        assertEquals("https://api.example.com/openapi.json",
                service.at("/connection/config/openAPISchemaConnection/openAPISchemaURL").asText());
        assertEquals("token-value", service.at("/connection/config/token").asText());
        assertEquals(true, service.at("/connection/config/supportsMetadataExtraction").asBoolean());
        assertEquals("ApiMetadata", pipeline.at("/sourceConfig/config/type").asText());
    }

    @Test
    void rejectsMissingOpenApiSpecUrl() {
        DataSource dataSource = source(42L, """
                {
                  "baseUrl": "https://api.example.com",
                  "authenticationType": "NONE"
                }
                """);

        MetadataIntegrationException error = assertThrows(
                MetadataIntegrationException.class,
                () -> adapter.serviceRequest(dataSource, "st_ds_42"));

        assertEquals(MetadataErrorCode.SOURCE_CONNECTION_ERROR, error.getErrorCode());
    }

    private static DataSource source(Long id, String connectionParams) {
        DataSource dataSource = new DataSource();
        dataSource.setId(id);
        dataSource.setName("orders-api");
        dataSource.setDbType(DbType.HTTP);
        dataSource.setConnectionParams(connectionParams);
        return dataSource;
    }
}
