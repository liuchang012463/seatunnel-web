package org.apache.seatunnel.plugin.datasource.vastbase.param;

import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VastbaseParamConverterTest {

    @Test
    void createsPostgresqlCompatibleUrlAndExplicitVastbaseType() {
        BaseConnectionParam result = new VastbaseParamConverter().createConnectionParams(
                "{\"host\":\"192.168.100.95\",\"port\":\"25432\","
                        + "\"database\":\"postgres\",\"user\":\"postgres\","
                        + "\"password\":\"secret\",\"schemaName\":\"public\"}");

        assertEquals(DbType.VASTBASE, result.getDbType());
        assertEquals("jdbc:postgresql://192.168.100.95:25432/postgres", result.getUrl());
        assertEquals("org.postgresql.Driver", result.getDriver());
    }
}
