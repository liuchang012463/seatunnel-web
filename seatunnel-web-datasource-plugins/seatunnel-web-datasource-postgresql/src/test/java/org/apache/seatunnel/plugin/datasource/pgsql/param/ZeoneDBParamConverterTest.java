package org.apache.seatunnel.plugin.datasource.pgsql.param;

import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ZeoneDBParamConverterTest {

    private static final String CONNECTION_JSON = """
            {
              "host": "zeonedb.example",
              "port": "5432",
              "database": "warehouse",
              "user": "reader",
              "password": "secret"
            }
            """;

    @Test
    void retainsZeoneDbIdentityAndPostgresDriverDefaults() {
        BaseConnectionParam connectionParam =
                new ZeoneDBParamConverter().createConnectionParams(CONNECTION_JSON);

        assertEquals(DbType.ZEONEDB, connectionParam.getDbType());
        assertEquals("jdbc:postgresql://zeonedb.example:5432/warehouse", connectionParam.getUrl());
        assertEquals("org.postgresql.Driver", connectionParam.getDriver());
    }

    @Test
    void processorUsesExplicitZeoneDbTypeForPostgresCompatibleUrls() {
        ZeoneDBDataSourceProcessor processor = new ZeoneDBDataSourceProcessor();

        assertEquals(DbType.ZEONEDB, processor.getDbType());
        assertFalse(processor.acceptsURL("jdbc:postgresql://zeonedb.example:5432/warehouse"));
        assertEquals(
                DbType.ZEONEDB,
                processor.getParamConverter().createConnectionParams(CONNECTION_JSON).getDbType());
    }
}
