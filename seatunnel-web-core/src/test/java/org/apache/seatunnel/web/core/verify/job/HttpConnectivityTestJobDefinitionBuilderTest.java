package org.apache.seatunnel.web.core.verify.job;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpConnectivityTestJobDefinitionBuilderTest {

    @Test
    void shouldSupportHttpDatasource() {
        assertTrue(new HttpConnectivityTestJobDefinitionBuilder().supports(DbType.HTTP));
    }
}
