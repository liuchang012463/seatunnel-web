package org.apache.seatunnel.web.core.verify;

import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyContext;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaDatasourceConnectivityVerificationStrategyTest {

    @Test
    void shouldSupportKafkaDatasource() {
        DatasourceVerifyContext context = DatasourceVerifyContext.builder()
                .dbType(DbType.KAFKA)
                .pluginName("KAFKA")
                .role("SOURCE")
                .build();

        assertTrue(new KafkaDatasourceConnectivityVerificationStrategy().supports(context));
    }
}
