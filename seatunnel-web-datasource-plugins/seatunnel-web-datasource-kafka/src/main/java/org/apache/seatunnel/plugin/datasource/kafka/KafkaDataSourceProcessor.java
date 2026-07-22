package org.apache.seatunnel.plugin.datasource.kafka;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.analysis.JobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilderFactory;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRuleFactory;
import org.apache.seatunnel.plugin.datasource.kafka.analysis.KafkaJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.kafka.catalog.KafkaCatalog;
import org.apache.seatunnel.plugin.datasource.kafka.client.KafkaAdminClientFacade;
import org.apache.seatunnel.plugin.datasource.kafka.option.KafkaOptions;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaConnectionParam;
import org.apache.seatunnel.plugin.datasource.kafka.param.KafkaConnectionParamConverter;
import org.apache.seatunnel.web.common.config.OptionRule;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Optional;

@AutoService(DataSourceProcessor.class)
public class KafkaDataSourceProcessor implements DataSourceProcessor {

    private final KafkaConnectionParamConverter converter = new KafkaConnectionParamConverter();
    private final KafkaAdminClientFacade adminClient = new KafkaAdminClientFacade();

    @Override
    public DataSourceHoconBuilder getQueryBuilder(String pluginName) {
        return DataSourceHoconBuilderFactory.getBuilder("KAFKA");
    }

    @Override
    public ConnectivityVerifier getConnectivityVerifier() {
        return adminClient;
    }

    @Override
    public ConnectionParamConverter getParamConverter() {
        return converter;
    }

    @Override
    public Optional<DataSourceCatalog> getCatalog(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof KafkaConnectionParam)) {
            throw new IllegalArgumentException("Invalid Kafka connection param type");
        }
        return Optional.of(new KafkaCatalog((KafkaConnectionParam) connectionParam, adminClient));
    }

    @Override
    public OptionRule sourceOptionRule(String pluginName) {
        return SourceOptionRuleFactory.getSourceOptionRule("KAFKA").sourceOptionRule();
    }

    @Override
    public OptionRule sinkOptionRule() {
        return OptionRule.builder()
                .required(KafkaOptions.BOOTSTRAP_SERVERS, KafkaOptions.TOPIC)
                .optional(KafkaOptions.FORMAT, KafkaOptions.SEMANTICS, KafkaOptions.TRANSACTION_PREFIX,
                        KafkaOptions.PARTITION, KafkaOptions.PARTITION_KEY_FIELDS, KafkaOptions.KAFKA_CONFIG)
                .exclusive(KafkaOptions.PARTITION, KafkaOptions.PARTITION_KEY_FIELDS)
                .build();
    }

    @Override
    public DbType getDbType() {
        return DbType.KAFKA;
    }

    @Override
    public DataSourceProcessor create() {
        return new KafkaDataSourceProcessor();
    }

    @Override
    public JobDefinitionAnalyzer getJobDefinitionAnalyzer() {
        return new KafkaJobDefinitionAnalyzer();
    }
}
