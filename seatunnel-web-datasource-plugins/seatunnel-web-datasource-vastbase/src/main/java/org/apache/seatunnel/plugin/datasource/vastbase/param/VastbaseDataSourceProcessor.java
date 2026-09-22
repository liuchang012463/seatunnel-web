package org.apache.seatunnel.plugin.datasource.vastbase.param;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractDataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcParamConverter;
import org.apache.seatunnel.plugin.datasource.vastbase.analysis.VastbaseJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.vastbase.connection.VastbaseConnectionProvider;
import org.apache.seatunnel.plugin.datasource.vastbase.metadata.VastbaseCatalog;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

@AutoService(DataSourceProcessor.class)
public class VastbaseDataSourceProcessor extends AbstractDataSourceProcessor {

    private final JdbcConnectionProvider connectionManager = new VastbaseConnectionProvider();
    private final JdbcParamConverter paramConverter = new VastbaseParamConverter();
    private final JobDefinitionAnalyzer jobDefinitionAnalyzer = new VastbaseJobDefinitionAnalyzer();

    @Override
    public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder(pluginName);
    }

    @Override
    public JdbcConnectionProvider getConnectionManager() {
        return connectionManager;
    }

    @Override
    public JdbcParamConverter getParamConverter() {
        return paramConverter;
    }

    @Override
    public JdbcCatalog getMetadataService(BaseConnectionParam connectionParam) {
        return new VastbaseCatalog(connectionParam, connectionManager);
    }

    @Override
    public DbType getDbType() {
        return DbType.VASTBASE;
    }

    /**
     * Vastbase's production JDBC URL is jdbc:postgresql://. The Web request
     * carries DbType.VASTBASE explicitly, so do not claim generic PostgreSQL
     * URLs here or URL-based processor discovery would be ambiguous.
     */
    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:vastbase:");
    }

    @Override
    public DataSourceProcessor create() {
        return new VastbaseDataSourceProcessor();
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return jobDefinitionAnalyzer;
    }
}
