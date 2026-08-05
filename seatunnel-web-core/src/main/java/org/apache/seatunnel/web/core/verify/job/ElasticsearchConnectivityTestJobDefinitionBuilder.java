package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jakarta.annotation.Resource;
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

@Component
public class ElasticsearchConnectivityTestJobDefinitionBuilder
        implements ConnectivityTestJobDefinitionBuilder {

    @Resource private ConsoleSinkHoconBuilder consoleSinkHoconBuilder;
    @Resource private TestJobEnvConfigBuilder testJobEnvConfigBuilder;
    @Resource private SeaTunnelJobConfigAssembler seaTunnelJobConfigAssembler;

    @Override
    public boolean supports(DbType dbType) {
        return dbType == DbType.ELASTICSEARCH;
    }

    @Override
    public ConnectivityTestJob build(SeaTunnelClient client, DataSource datasource) {
        DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(DbType.ELASTICSEARCH);
        ConnectionParam param = DataSourceUtils.buildConnectionParams(
                DbType.ELASTICSEARCH, datasource.getConnectionParams());
        DataSourceCatalog catalog = processor.getCatalog(param)
                .orElseThrow(() -> new IllegalStateException("Elasticsearch Index Catalog 不可用"));
        List<OptionVO> indexes = catalog.listOptions();
        if (indexes.isEmpty()) {
            throw new IllegalStateException("Elasticsearch 中没有可用于验证的 Index");
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("index", String.valueOf(indexes.get(0).getValue()));
        Config connectionConfig = ConfigFactory.parseString(datasource.getConnectionParams());
        DataSourceHoconBuilder sourceBuilder = processor.getQueryBuilder("ELASTICSEARCH");
        Config source = sourceBuilder.buildSourceHocon(HoconBuildContext.builder()
                .connectionParam(datasource.getConnectionParams())
                .connectionConfig(connectionConfig)
                .nodeConfig(ConfigFactory.parseMap(node))
                .stage(HoconBuildStage.INSTANCE)
                .build());

        String jobName = "elasticsearch-connectivity-" + client.getId() + "-" + datasource.getId();
        String jobConfig = seaTunnelJobConfigAssembler.assemble(
                testJobEnvConfigBuilder.buildBatchEnv(),
                "Elasticsearch",
                source,
                consoleSinkHoconBuilder.pluginName(),
                consoleSinkHoconBuilder.build());
        return new ConnectivityTestJob(jobName, jobConfig, "hocon", true);
    }
}
