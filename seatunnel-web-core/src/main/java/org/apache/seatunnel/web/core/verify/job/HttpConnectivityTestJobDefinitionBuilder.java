package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jakarta.annotation.Resource;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.common.enums.HoconBuildStage;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.SeaTunnelClient;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HttpConnectivityTestJobDefinitionBuilder
        implements ConnectivityTestJobDefinitionBuilder {

    @Resource private ConsoleSinkHoconBuilder consoleSinkHoconBuilder;
    @Resource private TestJobEnvConfigBuilder testJobEnvConfigBuilder;
    @Resource private SeaTunnelJobConfigAssembler seaTunnelJobConfigAssembler;

    @Override
    public boolean supports(DbType dbType) {
        return dbType == DbType.HTTP;
    }

    @Override
    public ConnectivityTestJob build(SeaTunnelClient client, DataSource datasource) {
        DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(DbType.HTTP);
        DataSourceHoconBuilder sourceBuilder = processor.getQueryBuilder("HTTP");
        Config connectionConfig = ConfigFactory.parseString(datasource.getConnectionParams());
        String healthCheckPath = connectionConfig.hasPath("healthCheckPath")
                ? connectionConfig.getString("healthCheckPath") : "";

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", healthCheckPath);
        node.put("method", "GET");
        node.put("format", "text");
        Config source = sourceBuilder.buildSourceHocon(HoconBuildContext.builder()
                .connectionParam(datasource.getConnectionParams())
                .connectionConfig(connectionConfig)
                .nodeConfig(ConfigFactory.parseMap(node))
                .stage(HoconBuildStage.INSTANCE)
                .build());
        String jobName = "http-connectivity-" + client.getId() + "-" + datasource.getId();
        String jobConfig = seaTunnelJobConfigAssembler.assemble(
                testJobEnvConfigBuilder.buildBatchEnv(), "Http", source,
                consoleSinkHoconBuilder.pluginName(), consoleSinkHoconBuilder.build());
        return new ConnectivityTestJob(jobName, jobConfig, "hocon", true);
    }
}
