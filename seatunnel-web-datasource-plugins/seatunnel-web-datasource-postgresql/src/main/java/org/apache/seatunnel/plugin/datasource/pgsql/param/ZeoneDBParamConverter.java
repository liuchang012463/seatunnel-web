package org.apache.seatunnel.plugin.datasource.pgsql.param;

import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

/** Reuses PostgreSQL JDBC fields and URL construction while retaining ZeoneDB identity. */
public class ZeoneDBParamConverter extends PgSQLParamConverter {

    @Override
    public BaseConnectionParam createConnectionParams(String connectionJson) {
        BaseConnectionParam connectionParam = super.createConnectionParams(connectionJson);
        connectionParam.setDbType(DbType.ZEONEDB);
        return connectionParam;
    }
}
