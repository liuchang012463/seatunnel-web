package org.apache.seatunnel.plugin.datasource.http;

import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.apache.seatunnel.web.common.utils.NonNullFunctions;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpServiceLoaderTest {

    @Test
    void shouldDiscoverProcessorBuilderAndSourceOptionRule() {
        assertTrue(ServiceLoader.load(DataSourceProcessor.class).stream()
                .map(NonNullFunctions.from(ServiceLoader.Provider::get)).filter(item -> item != null)
                .anyMatch(processor -> processor.getDbType() == DbType.HTTP));
        assertTrue(ServiceLoader.load(DataSourceHoconBuilder.class).stream()
                .map(NonNullFunctions.from(ServiceLoader.Provider::get)).filter(item -> item != null)
                .anyMatch(builder -> "HTTP".equals(builder.pluginName())));
        assertTrue(ServiceLoader.load(SourceOptionRule.class).stream()
                .map(NonNullFunctions.from(ServiceLoader.Provider::get)).filter(item -> item != null)
                .anyMatch(rule -> "HTTP".equals(rule.pluginName())));
    }
}
