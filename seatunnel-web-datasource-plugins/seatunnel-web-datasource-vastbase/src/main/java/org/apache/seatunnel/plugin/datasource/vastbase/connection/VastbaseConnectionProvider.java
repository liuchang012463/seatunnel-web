package org.apache.seatunnel.plugin.datasource.vastbase.connection;

import org.apache.seatunnel.plugin.datasource.api.constants.DataSourceConstants;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.vastbase.param.VastbaseConnectionParam;

import java.sql.Connection;
import java.sql.SQLException;

public class VastbaseConnectionProvider
        extends AbstractJdbcConnectionProvider<VastbaseConnectionParam> {

    @Override
    protected String defaultDriverClass() {
        return DataSourceConstants.ORG_POSTGRESQL_DRIVER;
    }

    @Override
    protected String resolveDriverLocation(VastbaseConnectionParam param) {
        String location = param.getDriverLocation();
        if (location == null || location.isBlank()) {
            location = "Vastbase-G100-2.16_pg_2026062910.jar";
        }
        return location.contains("/") || location.contains("\\")
                ? location
                : defaultBaseUrl() + location;
    }

    @Override
    protected void validateConnectionScope(Connection connection, VastbaseConnectionParam param)
            throws SQLException {
        validateConfiguredSchema(connection, null, param.getSchemaName());
    }
}
