package org.apache.seatunnel.plugin.datasource.pgsql.connection;

import org.apache.seatunnel.plugin.datasource.api.constants.DataSourceConstants;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.pgsql.param.PgSQLConnectionParam;

import java.sql.Connection;
import java.sql.SQLException;

public class PgSQLConnectionProvider
        extends AbstractJdbcConnectionProvider<PgSQLConnectionParam> {

    @Override
    protected String defaultDriverClass() {
        return DataSourceConstants.ORG_POSTGRESQL_DRIVER;
    }

    @Override
    protected String resolveDriverLocation(PgSQLConnectionParam t) {
        return defaultBaseUrl() + t.getDriverLocation();
    }

    @Override
    protected void validateConnectionScope(Connection connection, PgSQLConnectionParam param)
            throws SQLException {
        validateConfiguredSchema(connection, null, param.getSchemaName());
    }
}
