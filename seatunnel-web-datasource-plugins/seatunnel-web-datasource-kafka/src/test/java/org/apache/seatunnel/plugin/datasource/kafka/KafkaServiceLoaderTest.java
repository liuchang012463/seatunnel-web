package org.apache.seatunnel.plugin.datasource.kafka;

import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaServiceLoaderTest {

    @Test
    void shouldRegisterAllKafkaSpiEntries() {
        assertTrue(has(DataSourceProcessor.class, item -> item.getDbType() == DbType.KAFKA));
        assertTrue(has(DataSourceHoconBuilder.class, item -> "KAFKA".equals(item.pluginName())));
        assertTrue(has(SourceOptionRule.class, item -> "KAFKA".equals(item.pluginName())));
    }

    private <T> boolean has(Class<T> type, java.util.function.Predicate<T> predicate) {
        return StreamSupport.stream(ServiceLoader.load(type).spliterator(), false).anyMatch(predicate);
    }
}
