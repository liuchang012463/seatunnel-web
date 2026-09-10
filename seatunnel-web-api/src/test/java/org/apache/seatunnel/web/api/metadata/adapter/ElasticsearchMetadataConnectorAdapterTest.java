package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchMetadataConnectorAdapterTest {

    private final ElasticsearchMetadataConnectorAdapter adapter = new ElasticsearchMetadataConnectorAdapter();

    @Test
    void buildsFixed11210ElasticSearchServiceWithBasicAuth() {
        DataSource dataSource = source(
                "{\"hosts\":\"http://es1.example:9200,http://es2.example:9200\","
                        + "\"authType\":\"BASIC\",\"username\":\"elastic\",\"password\":\"secret\"}");

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_30");
        JsonNode pipeline = adapter.metadataPipelineRequest("st_ds_30_metadata", "uuid-1", "st_ds_30");

        assertEquals("ElasticSearch", service.at("/serviceType").asText());
        assertEquals("ElasticSearch", service.at("/connection/config/type").asText());
        assertEquals("http://es1.example:9200", service.at("/connection/config/hostPort").asText());
        assertEquals("elastic", service.at("/connection/config/authType/username").asText());
        assertEquals("secret", service.at("/connection/config/authType/password").asText());
        assertEquals(true, service.at("/connection/config/supportsMetadataExtraction").asBoolean());
        assertEquals(MetadataServiceCategory.SEARCH, adapter.serviceCategory());
        assertFalse(adapter.supportsProfiler());
        assertEquals("SearchMetadata", pipeline.at("/sourceConfig/config/type").asText());
        assertEquals("searchService", pipeline.at("/service/type").asText());
    }

    @Test
    void buildsFixed11210ElasticSearchServiceWithApiKeyAuth() {
        DataSource dataSource = source(
                "{\"hosts\":[\"https://es.example:9200\"],"
                        + "\"authType\":\"API_KEY\",\"apiKeyId\":\"id-1\",\"apiKey\":\"key-1\"}");

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_31");

        assertEquals("https://es.example:9200", service.at("/connection/config/hostPort").asText());
        assertEquals("id-1", service.at("/connection/config/authType/apiKeyId").asText());
        assertEquals("key-1", service.at("/connection/config/authType/apiKey").asText());
    }

    @Test
    void buildsFixed11210ElasticSearchServiceWithEncodedApiKeyAuth() {
        DataSource dataSource = source(
                "{\"hosts\":\"es.example:9200\","
                        + "\"authType\":\"API_KEY_ENCODED\",\"apiKeyEncoded\":\"encoded-key\"}");

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_32");

        assertEquals("http://es.example:9200", service.at("/connection/config/hostPort").asText());
        assertEquals("encoded-key", service.at("/connection/config/authType/apiKey").asText());
        assertTrue(service.at("/connection/config/authType/apiKeyId").isMissingNode());
    }

    private static DataSource source(String connectionParams) {
        DataSource dataSource = new DataSource();
        dataSource.setId(30L);
        dataSource.setName("search");
        dataSource.setDbType(DbType.ELASTICSEARCH);
        dataSource.setConnectionParams(connectionParams);
        return dataSource;
    }
}
