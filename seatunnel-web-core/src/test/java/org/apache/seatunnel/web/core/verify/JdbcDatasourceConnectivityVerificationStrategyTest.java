package org.apache.seatunnel.web.core.verify;

import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyContext;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDatasourceConnectivityVerificationStrategyTest {

    @Test
    void shouldSupportGenericJdbcPlugin() {
        DatasourceVerifyContext context = DatasourceVerifyContext.builder()
                .dbType(DbType.JDBC)
                .pluginName("JDBC-JDBC")
                .role("SOURCE")
                .build();

        assertTrue(new JdbcDatasourceConnectivityVerificationStrategy().supports(context));
    }
}
