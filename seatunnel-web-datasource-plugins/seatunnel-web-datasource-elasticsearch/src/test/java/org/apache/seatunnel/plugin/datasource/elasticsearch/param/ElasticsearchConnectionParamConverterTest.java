package org.apache.seatunnel.plugin.datasource.elasticsearch.param;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElasticsearchConnectionParamConverterTest {

    private final ElasticsearchConnectionParamConverter converter =
            new ElasticsearchConnectionParamConverter();

    @Test
    void emptyJsonCreatesTypedParamWithoutNetworkAccess() {
        ElasticsearchConnectionParam param = converter.createConnectionParams("{}");

        assertNotNull(param);
        assertEquals(DbType.ELASTICSEARCH, param.getDbType());
        assertEquals(10000, param.getConnectTimeoutMs());
        assertEquals(60000, param.getSocketTimeoutMs());
    }

    @Test
    void acceptsArrayHostsAndApiKeyAuthentication() {
        ElasticsearchConnectionParam param = converter.createConnectionParams(
                "{\"hosts\":[\"localhost:9200\",\"http://es-2:9200\"],"
                        + "\"authType\":\"API_KEY_ENCODED\","
                        + "\"apiKeyEncoded\":\"encoded\"}");

        converter.checkDatasourceParam(param);

        assertEquals(2, param.hostList().size());
        assertEquals("http://localhost:9200", param.hostList().get(0));
        assertEquals("API_KEY_ENCODED", param.getAuthType().name());
    }

    @Test
    void rejectsCredentialsWithNoneAuthentication() {
        ElasticsearchConnectionParam param = converter.createConnectionParams(
                "{\"hosts\":\"localhost:9200\",\"username\":\"elastic\"}");

        assertThrows(IllegalArgumentException.class,
                () -> converter.checkDatasourceParam(param));
    }

    @Test
    void rejectsInvalidHostScheme() {
        ElasticsearchConnectionParam param = converter.createConnectionParams(
                "{\"hosts\":\"ftp://localhost:9200\"}");

        assertThrows(IllegalArgumentException.class,
                () -> converter.checkDatasourceParam(param));
    }
}
