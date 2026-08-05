package org.apache.seatunnel.web.core.verify;

import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyContext;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchDatasourceConnectivityVerificationStrategyTest {

    @Test
    void shouldSupportElasticsearchDatasource() {
        DatasourceVerifyContext context = DatasourceVerifyContext.builder()
                .dbType(DbType.ELASTICSEARCH)
                .pluginName("ELASTICSEARCH")
                .role("SOURCE")
                .build();

        assertTrue(new ElasticsearchDatasourceConnectivityVerificationStrategy().supports(context));
    }
}
