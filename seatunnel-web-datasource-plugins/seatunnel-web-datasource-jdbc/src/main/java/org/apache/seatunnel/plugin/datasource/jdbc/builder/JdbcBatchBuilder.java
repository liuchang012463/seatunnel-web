package org.apache.seatunnel.plugin.datasource.jdbc.builder;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.hocon.AbstractJdbcBatchBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;

@AutoService(DataSourceHoconBuilder.class)
public class JdbcBatchBuilder extends AbstractJdbcBatchBuilder {

    public static final String PLUGIN_NAME = "JDBC-JDBC";

    @Override
    protected String defaultDriver() {
        return "";
    }

    @Override
    public String pluginName() {
        return PLUGIN_NAME;
    }
}
