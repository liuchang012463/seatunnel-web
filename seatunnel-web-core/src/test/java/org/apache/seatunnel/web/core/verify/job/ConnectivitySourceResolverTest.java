package org.apache.seatunnel.web.core.verify.job;

import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectivitySourceResolverTest {

    @Test
    void shouldResolveGenericJdbcSourceConfiguration() {
        assertEquals(
                "JDBC-JDBC",
                new ConnectivitySourceBuilderResolver().resolveBuilderKey(DbType.JDBC));
        assertEquals(
                "Jdbc",
                new ConnectivitySourcePluginNameResolver().resolvePluginName(DbType.JDBC));
    }
}
