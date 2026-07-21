package org.apache.seatunnel.plugin.datasource.jdbc.connection;

import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.jdbc.param.JdbcConnectionParam;

public class JdbcConnectionProviderImpl
        extends AbstractJdbcConnectionProvider<JdbcConnectionParam> {

    @Override
    protected String defaultDriverClass() {
        return "";
    }

    @Override
    protected String resolveDriverLocation(JdbcConnectionParam connectionParam) {
        return connectionParam.getDriverLocation();
    }
}
