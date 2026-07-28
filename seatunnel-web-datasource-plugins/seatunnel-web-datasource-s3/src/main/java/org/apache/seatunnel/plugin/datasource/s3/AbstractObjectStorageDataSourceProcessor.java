package org.apache.seatunnel.plugin.datasource.s3;

import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.s3.analysis.ObjectStorageJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.s3.catalog.ObjectStorageCatalog;
import org.apache.seatunnel.plugin.datasource.s3.client.ObjectStorageClient;
import org.apache.seatunnel.plugin.datasource.s3.param.ObjectStorageConnectionParam;
import org.apache.seatunnel.web.common.config.Option;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.common.config.Options;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.util.Optional;

public abstract class AbstractObjectStorageDataSourceProcessor implements DataSourceProcessor {
    private static final Option<String> ENDPOINT =
            Options.key("fs.s3a.endpoint").stringType().noDefaultValue();
    private static final Option<String> PROVIDER =
            Options.key("fs.s3a.aws.credentials.provider").stringType().noDefaultValue();
    private static final Option<String> BUCKET =
            Options.key("bucket").stringType().noDefaultValue();
    private static final Option<String> PATH =
            Options.key("path").stringType().noDefaultValue();
    private static final Option<String> FORMAT =
            Options.key("file_format_type").stringType().defaultValue("binary");

    protected abstract ConnectionParamConverter converter();

    protected abstract ObjectStorageClient client();

    @Override
    public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder("S3File");
    }

    @Override
    public ConnectivityVerifier getConnectivityVerifier() {
        return client();
    }

    @Override
    public ConnectionParamConverter getParamConverter() {
        return converter();
    }

    @Override
    public Optional<DataSourceCatalog> getCatalog(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof ObjectStorageConnectionParam)) {
            throw new IllegalArgumentException("Invalid S3-compatible connection param type");
        }
        return Optional.of(new ObjectStorageCatalog(
                (ObjectStorageConnectionParam) connectionParam,
                client()));
    }

    @Override
    public OptionRule sourceOptionRule(String pluginName) {
        return OptionRule.builder()
                .required(ENDPOINT, PROVIDER, BUCKET, PATH)
                .optional(FORMAT)
                .build();
    }

    @Override
    public OptionRule sinkOptionRule() {
        return sourceOptionRule("S3File");
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return new ObjectStorageJobDefinitionAnalyzer(getDbType());
    }
}
