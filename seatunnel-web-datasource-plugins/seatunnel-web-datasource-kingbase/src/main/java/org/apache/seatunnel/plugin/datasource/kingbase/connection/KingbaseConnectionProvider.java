package org.apache.seatunnel.plugin.datasource.kingbase.connection;

import org.apache.seatunnel.plugin.datasource.api.constants.DataSourceConstants;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.kingbase.param.KingbaseConnectionParam;

import java.sql.Connection;
import java.sql.SQLException;

public class KingbaseConnectionProvider
        extends AbstractJdbcConnectionProvider<KingbaseConnectionParam> {

    @Override
    protected String defaultDriverClass() {
        return DataSourceConstants.COM_KINGBASE_JDBC_DRIVER;
    }

    @Override
    protected String resolveDriverLocation(KingbaseConnectionParam t) {
        return defaultBaseUrl() + t.getDriverLocation();
    }

    @Override
    protected void validateConnectionScope(Connection connection, KingbaseConnectionParam param)
            throws SQLException {
        validateConfiguredSchema(connection, null, param.getSchemaName());
    }
}
