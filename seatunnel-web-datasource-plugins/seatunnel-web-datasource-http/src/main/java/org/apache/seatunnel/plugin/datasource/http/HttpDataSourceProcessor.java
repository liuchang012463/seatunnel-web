package org.apache.seatunnel.plugin.datasource.http;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRuleFactory;
import org.apache.seatunnel.plugin.datasource.http.analysis.HttpJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.http.client.HttpConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParamConverter;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Optional;

@AutoService(DataSourceProcessor.class)
public class HttpDataSourceProcessor implements DataSourceProcessor {

    private final HttpConnectionParamConverter converter = new HttpConnectionParamConverter();
    private final HttpConnectivityVerifier verifier = new HttpConnectivityVerifier();

    @Override
    public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder("HTTP");
    }

    @Override
    public ConnectivityVerifier getConnectivityVerifier() {
        return verifier;
    }

    @Override
    public ConnectionParamConverter getParamConverter() {
        return converter;
    }

    @Override
    public Optional<DataSourceCatalog> getCatalog(ConnectionParam connectionParam) {
        return Optional.empty();
    }

    @Override
    public boolean supportsCatalog() {
        return false;
    }

    @Override
    public OptionRule sourceOptionRule(String pluginName) {
        return SourceOptionRuleFactory.getSourceOptionRule("HTTP").sourceOptionRule();
    }

    @Override
    public OptionRule sinkOptionRule() {
        return OptionRule.builder().build();
    }

    @Override
    public DbType getDbType() {
        return DbType.HTTP;
    }

    @Override
    public DataSourceProcessor create() {
        return new HttpDataSourceProcessor();
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return new HttpJobDefinitionAnalyzer();
    }
}
