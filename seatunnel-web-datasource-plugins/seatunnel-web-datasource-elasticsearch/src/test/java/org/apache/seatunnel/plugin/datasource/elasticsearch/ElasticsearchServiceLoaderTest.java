package org.apache.seatunnel.plugin.datasource.elasticsearch;

import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchServiceLoaderTest {

    @Test
    void shouldRegisterAllElasticsearchSpiEntries() {
        assertTrue(has(DataSourceProcessor.class, item -> item.getDbType() == DbType.ELASTICSEARCH));
        assertTrue(has(DataSourceHoconBuilder.class,
                item -> "ELASTICSEARCH".equals(item.pluginName())));
        assertTrue(has(SourceOptionRule.class,
                item -> "ELASTICSEARCH".equals(item.pluginName())));
    }

    private <T> boolean has(Class<T> type, Predicate<T> predicate) {
        return StreamSupport.stream(ServiceLoader.load(type).spliterator(), false)
                .anyMatch(predicate);
    }
}
