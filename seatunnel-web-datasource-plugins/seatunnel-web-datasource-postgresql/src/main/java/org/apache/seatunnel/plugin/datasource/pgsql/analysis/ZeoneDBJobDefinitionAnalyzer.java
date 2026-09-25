package org.apache.seatunnel.plugin.datasource.pgsql.analysis;

import org.apache.seatunnel.web.spi.enums.DbType;

/** Uses PostgreSQL batch job analysis rules for ZeoneDB. */
public class ZeoneDBJobDefinitionAnalyzer extends PostgreSQLJobDefinitionAnalyzer {

    @Override
    protected DbType dbType() {
        return DbType.ZEONEDB;
    }
}
