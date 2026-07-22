package org.apache.seatunnel.plugin.datasource.postgresql.cdc;

import org.apache.seatunnel.plugin.datasource.api.cdc.CdcDatasourcePrecheckProvider;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.plugin.datasource.postgresql.cdc.builder.PostgreSqlCdcSourceBuilder;
import org.apache.seatunnel.plugin.datasource.postgresql.cdc.option.PostgreSqlCdcSourceOptionRule;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSqlCdcServiceLoaderTest {

    @Test
    void pluginContractIsRegistered() {
        assertEquals("POSTGRESQL-CDC", new PostgreSqlCdcSourceBuilder().pluginName());
        assertEquals("POSTGRESQL-CDC", new PostgreSqlCdcSourceOptionRule().pluginName());

        assertTrue(ServiceLoader.load(DataSourceHoconBuilder.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(PostgreSqlCdcSourceBuilder.class::isInstance));
        assertTrue(ServiceLoader.load(SourceOptionRule.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(PostgreSqlCdcSourceOptionRule.class::isInstance));
        assertTrue(ServiceLoader.load(CdcDatasourcePrecheckProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(PostgreSqlCdcPrecheckProvider.class::isInstance));
    }

    @Test
    void sourceOptionRuleDoesNotRegisterDuplicateOptions() {
        assertDoesNotThrow(() -> new PostgreSqlCdcSourceOptionRule().sourceOptionRule());
    }
}
