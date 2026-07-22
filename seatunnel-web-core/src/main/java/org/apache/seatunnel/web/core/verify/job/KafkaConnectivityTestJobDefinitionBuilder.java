package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.DataSourceCatalog;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.common.enums.HoconBuildStage;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.SeaTunnelClient;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class KafkaConnectivityTestJobDefinitionBuilder implements ConnectivityTestJobDefinitionBuilder {

    @Resource private ConsoleSinkHoconBuilder consoleSinkHoconBuilder;
    @Resource private TestJobEnvConfigBuilder testJobEnvConfigBuilder;
    @Resource private SeaTunnelJobConfigAssembler seaTunnelJobConfigAssembler;

    @Override
    public boolean supports(DbType dbType) {
        return dbType == DbType.KAFKA;
    }

    @Override
    public ConnectivityTestJob build(SeaTunnelClient client, DataSource datasource) {
        return build(client, datasource, null);
    }

    @Override
    public ConnectivityTestJob build(SeaTunnelClient client, DataSource datasource, String requestedTopic) {
        DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(DbType.KAFKA);
        ConnectionParam param = DataSourceUtils.buildConnectionParams(DbType.KAFKA, datasource.getConnectionParams());
        String topic = StringUtils.defaultIfBlank(requestedTopic, firstTopic(processor, param));

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("topic", topic);
        node.put("consumerGroup", "seatunnel-web-connectivity-" + UUID.randomUUID());
        node.put("startMode", "latest");
        node.put("commitOnCheckpoint", false);
        node.put("format", "json");

        Config connectionConfig = ConfigFactory.parseString(datasource.getConnectionParams());
        DataSourceHoconBuilder sourceBuilder = processor.getQueryBuilder("KAFKA");
        Config source = sourceBuilder.buildSourceHocon(HoconBuildContext.builder()
                .connectionParam(datasource.getConnectionParams())
                .connectionConfig(connectionConfig)
                .nodeConfig(ConfigFactory.parseMap(node))
                .stage(HoconBuildStage.INSTANCE)
                .build());
        String jobName = "kafka-connectivity-" + client.getId() + "-" + datasource.getId();
        String jobConfig = seaTunnelJobConfigAssembler.assemble(
                testJobEnvConfigBuilder.buildBatchEnv(), "Kafka", source,
                consoleSinkHoconBuilder.pluginName(), consoleSinkHoconBuilder.build());
        return new ConnectivityTestJob(jobName, jobConfig, "hocon", true);
    }

    private String firstTopic(DataSourceProcessor processor, ConnectionParam param) {
        DataSourceCatalog catalog = processor.getCatalog(param)
                .orElseThrow(() -> new IllegalStateException("Kafka Topic Catalog 不可用"));
        List<OptionVO> topics = catalog.listOptions();
        if (topics.isEmpty()) {
            throw new IllegalStateException("Kafka 中没有可用于验证的非内部 Topic，请在请求中指定 topic");
        }
        return String.valueOf(topics.get(0).getValue());
    }
}
