package org.apache.seatunnel.plugin.datasource.localfile;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.localfile.analysis.LocalFileJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.localfile.catalog.LocalFileCatalog;
import org.apache.seatunnel.plugin.datasource.localfile.client.LocalFileClient;
import org.apache.seatunnel.plugin.datasource.localfile.param.LocalFileConnectionParam;
import org.apache.seatunnel.plugin.datasource.localfile.param.LocalFileParamConverter;
import org.apache.seatunnel.web.common.config.Option;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.common.config.Options;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Optional;

@AutoService(DataSourceProcessor.class)
public class LocalFileDataSourceProcessor implements DataSourceProcessor {

    private static final Option<String> PATH = Options.key("path").stringType().noDefaultValue();
    private static final Option<String> FORMAT = Options.key("file_format_type").stringType().defaultValue("binary");
    private static final Option<String> FILE_FILTER_PATTERN =
            Options.key("file_filter_pattern").stringType().noDefaultValue();
    private static final Option<String> FILENAME_EXTENSION =
            Options.key("filename_extension").stringType().noDefaultValue();
    private static final Option<Integer> BINARY_CHUNK_SIZE =
            Options.key("binary_chunk_size").intType().noDefaultValue();
    private static final Option<Boolean> BINARY_COMPLETE_FILE_MODE =
            Options.key("binary_complete_file_mode").booleanType().noDefaultValue();

    private final LocalFileParamConverter converter = new LocalFileParamConverter();
    private final LocalFileClient client = new LocalFileClient();

    @Override
    public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder("LocalFile");
    }

    @Override
    public ConnectivityVerifier getConnectivityVerifier() {
        return client;
    }

    @Override
    public ConnectionParamConverter getParamConverter() {
        return converter;
    }

    @Override
    public Optional<DataSourceCatalog> getCatalog(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof LocalFileConnectionParam)) {
            throw new IllegalArgumentException("Invalid LOCAL_FILE connection param type");
        }
        return Optional.of(new LocalFileCatalog((LocalFileConnectionParam) connectionParam));
    }

    @Override
    public OptionRule sourceOptionRule(String pluginName) {
        return OptionRule.builder()
                .required(PATH)
                .optional(FORMAT, FILE_FILTER_PATTERN, FILENAME_EXTENSION, BINARY_CHUNK_SIZE, BINARY_COMPLETE_FILE_MODE)
                .build();
    }

    @Override
    public OptionRule sinkOptionRule() {
        return OptionRule.builder().required(PATH).optional(FORMAT).build();
    }

    @Override
    public DbType getDbType() {
        return DbType.LOCAL_FILE;
    }

    @Override
    public DataSourceProcessor create() {
        return new LocalFileDataSourceProcessor();
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return new LocalFileJobDefinitionAnalyzer();
    }
}
