package org.apache.seatunnel.plugin.datasource.ftp;

import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.ftp.analysis.RemoteFileJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.ftp.catalog.RemoteFileCatalog;
import org.apache.seatunnel.plugin.datasource.ftp.client.RemoteFileClient;
import org.apache.seatunnel.plugin.datasource.ftp.param.RemoteFileConnectionParam;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.common.config.Option;
import org.apache.seatunnel.web.common.config.Options;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Optional;

public abstract class AbstractRemoteFileDataSourceProcessor implements DataSourceProcessor {
    private static final Option<String> HOST = Options.key("host").stringType().noDefaultValue();
    private static final Option<Integer> PORT = Options.key("port").intType().noDefaultValue();
    private static final Option<String> USER = Options.key("user").stringType().noDefaultValue();
    private static final Option<String> PASSWORD = Options.key("password").stringType().noDefaultValue();
    private static final Option<String> PATH = Options.key("path").stringType().noDefaultValue();
    private static final Option<String> FORMAT = Options.key("file_format_type").stringType().defaultValue("binary");

    protected abstract String connectorName();
    protected abstract ConnectionParamConverter converter();
    protected abstract RemoteFileClient client();

    @Override public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder(connectorName());
    }
    @Override public ConnectivityVerifier getConnectivityVerifier() { return client(); }
    @Override public ConnectionParamConverter getParamConverter() { return converter(); }
    @Override public Optional<DataSourceCatalog> getCatalog(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof RemoteFileConnectionParam)) {
            throw new IllegalArgumentException("Invalid remote file connection param type");
        }
        return Optional.of(new RemoteFileCatalog((RemoteFileConnectionParam) connectionParam, client()));
    }
    @Override public OptionRule sourceOptionRule(String pluginName) {
        return OptionRule.builder().required(HOST, PORT, USER, PASSWORD, PATH).optional(FORMAT).build();
    }
    @Override public OptionRule sinkOptionRule() { return sourceOptionRule(connectorName()); }
    @Override public JobDefinitionAnalyzer getJobDefinitionAnalyzer() { return new RemoteFileJobDefinitionAnalyzer(getDbType()); }
}
