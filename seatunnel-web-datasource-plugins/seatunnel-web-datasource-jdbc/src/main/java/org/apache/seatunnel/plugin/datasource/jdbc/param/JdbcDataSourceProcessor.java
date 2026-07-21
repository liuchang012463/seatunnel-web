package org.apache.seatunnel.plugin.datasource.jdbc.param;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractDataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcParamConverter;
import org.apache.seatunnel.plugin.datasource.jdbc.analysis.JdbcJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.jdbc.connection.JdbcConnectionProviderImpl;
import org.apache.seatunnel.plugin.datasource.jdbc.metadata.GenericJdbcCatalog;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

@AutoService(DataSourceProcessor.class)
public class JdbcDataSourceProcessor extends AbstractDataSourceProcessor {

    private final JdbcConnectionProvider connectionProvider = new JdbcConnectionProviderImpl();
    private final JdbcParamConverter paramConverter = new JdbcConnectionParamConverter();
    private final JobDefinitionAnalyzer jobDefinitionAnalyzer = new JdbcJobDefinitionAnalyzer();

    @Override
    public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder(pluginName);
    }

    @Override
    public JdbcConnectionProvider getConnectionManager() {
        return connectionProvider;
    }

    @Override
    public JdbcParamConverter getParamConverter() {
        return paramConverter;
    }

    @Override
    public JdbcCatalog getMetadataService(BaseConnectionParam connectionParam) {
        return new GenericJdbcCatalog(connectionParam, connectionProvider);
    }

    @Override
    public DbType getDbType() {
        return DbType.JDBC;
    }

    @Override
    public DataSourceProcessor create() {
        return new JdbcDataSourceProcessor();
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return jobDefinitionAnalyzer;
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.trim().toLowerCase().startsWith("jdbc:");
    }
}
