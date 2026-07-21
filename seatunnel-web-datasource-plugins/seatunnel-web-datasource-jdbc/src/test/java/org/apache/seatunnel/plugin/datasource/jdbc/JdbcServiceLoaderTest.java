package org.apache.seatunnel.plugin.datasource.jdbc;

import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.plugin.datasource.jdbc.builder.JdbcBatchBuilder;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcServiceLoaderTest {

    @Test
    void registersAllJdbcPluginEntryPoints() {
        assertTrue(StreamSupport.stream(
                        ServiceLoader.load(DataSourceProcessor.class).spliterator(), false)
                .anyMatch(processor -> processor.getDbType() == DbType.JDBC));
        assertTrue(StreamSupport.stream(
                        ServiceLoader.load(DataSourceHoconBuilder.class).spliterator(), false)
                .anyMatch(builder -> JdbcBatchBuilder.PLUGIN_NAME.equals(builder.pluginName())));
        assertTrue(StreamSupport.stream(
                        ServiceLoader.load(SourceOptionRule.class).spliterator(), false)
                .anyMatch(rule -> JdbcBatchBuilder.PLUGIN_NAME.equals(rule.pluginName())));
    }
}
