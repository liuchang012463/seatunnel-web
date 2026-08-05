package org.apache.seatunnel.plugin.datasource.elasticsearch;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRuleFactory;
import org.apache.seatunnel.plugin.datasource.elasticsearch.analysis.ElasticsearchJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.elasticsearch.catalog.ElasticsearchCatalog;
import org.apache.seatunnel.plugin.datasource.elasticsearch.client.ElasticsearchHttpClient;
import org.apache.seatunnel.plugin.datasource.elasticsearch.option.ElasticsearchOptions;
import org.apache.seatunnel.plugin.datasource.elasticsearch.param.ElasticsearchConnectionParam;
import org.apache.seatunnel.plugin.datasource.elasticsearch.param.ElasticsearchConnectionParamConverter;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Optional;

@AutoService(DataSourceProcessor.class)
public class ElasticsearchDataSourceProcessor implements DataSourceProcessor {

    private final ElasticsearchConnectionParamConverter converter =
            new ElasticsearchConnectionParamConverter();
    private final ElasticsearchHttpClient client = new ElasticsearchHttpClient();

    @Override
    public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder("ELASTICSEARCH");
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
        if (!(connectionParam instanceof ElasticsearchConnectionParam)) {
            throw new IllegalArgumentException("Invalid Elasticsearch connection param type");
        }
        return Optional.of(new ElasticsearchCatalog(
                (ElasticsearchConnectionParam) connectionParam, client));
    }

    @Override
    public OptionRule sourceOptionRule(String pluginName) {
        return SourceOptionRuleFactory.getSourceOptionRule("ELASTICSEARCH")
                .sourceOptionRule();
    }

    @Override
    public OptionRule sinkOptionRule() {
        return OptionRule.builder()
                .required(ElasticsearchOptions.HOSTS, ElasticsearchOptions.INDEX,
                        ElasticsearchOptions.SCHEMA_SAVE_MODE, ElasticsearchOptions.DATA_SAVE_MODE)
                .optional(ElasticsearchOptions.AUTH_TYPE, ElasticsearchOptions.USERNAME,
                        ElasticsearchOptions.PASSWORD, ElasticsearchOptions.API_KEY_ID,
                        ElasticsearchOptions.API_KEY, ElasticsearchOptions.API_KEY_ENCODED,
                        ElasticsearchOptions.INDEX_TYPE, ElasticsearchOptions.PRIMARY_KEYS,
                        ElasticsearchOptions.KEY_DELIMITER, ElasticsearchOptions.MAX_RETRY_COUNT,
                        ElasticsearchOptions.MAX_BATCH_SIZE, ElasticsearchOptions.TLS_VERIFY_CERTIFICATE,
                        ElasticsearchOptions.TLS_VERIFY_HOSTNAME, ElasticsearchOptions.VECTORIZATION_FIELDS,
                        ElasticsearchOptions.VECTOR_DIMENSIONS)
                .build();
    }

    @Override
    public DbType getDbType() {
        return DbType.ELASTICSEARCH;
    }

    @Override
    public DataSourceProcessor create() {
        return new ElasticsearchDataSourceProcessor();
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return new ElasticsearchJobDefinitionAnalyzer();
    }
}
