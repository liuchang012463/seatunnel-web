package org.apache.seatunnel.web.core.verify;

import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyContext;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpDatasourceConnectivityVerificationStrategyTest {

    @Test
    void shouldSupportHttpSourceOnly() {
        HttpDatasourceConnectivityVerificationStrategy strategy =
                new HttpDatasourceConnectivityVerificationStrategy();
        assertTrue(strategy.supports(context("SOURCE")));
        assertFalse(strategy.supports(context("SINK")));
    }

    private DatasourceVerifyContext context(String role) {
        return DatasourceVerifyContext.builder()
                .dbType(DbType.HTTP)
                .pluginName("HTTP")
                .role(role)
                .build();
    }
}
