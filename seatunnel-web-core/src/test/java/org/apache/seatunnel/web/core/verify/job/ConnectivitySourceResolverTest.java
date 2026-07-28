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

    @Test
    void shouldResolveKafkaSourceConfiguration() {
        assertEquals(
                "KAFKA",
                new ConnectivitySourceBuilderResolver().resolveBuilderKey(DbType.KAFKA));
        assertEquals(
                "Kafka",
                new ConnectivitySourcePluginNameResolver().resolvePluginName(DbType.KAFKA));
    }

    @Test
    void shouldResolveHttpSourceConfiguration() {
        assertEquals(
                "HTTP",
                new ConnectivitySourceBuilderResolver().resolveBuilderKey(DbType.HTTP));
        assertEquals(
                "Http",
                new ConnectivitySourcePluginNameResolver().resolvePluginName(DbType.HTTP));
    }
}
