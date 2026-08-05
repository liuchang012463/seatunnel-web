package org.apache.seatunnel.web.core.verify.job;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchConnectivityTestJobDefinitionBuilderTest {

    @Test
    void shouldSupportElasticsearchDatasource() {
        assertTrue(new ElasticsearchConnectivityTestJobDefinitionBuilder()
                .supports(DbType.ELASTICSEARCH));
    }
}
