package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.common.enums.HoconBuildStage;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.SeaTunnelClient;
import org.apache.seatunnel.web.core.verify.modal.DatasourceVerifyScope;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Doris 连通性测试任务构建器。
 *
 * <p>Doris 的客户端连通性验证使用 JDBC query port 上的轻量 SQL 探针。
 * 未提供任务表范围时执行 {@code SELECT 1}，不依赖任意业务表；显式提供
 * database/table 时执行 {@code LIMIT 0} 表访问检查。</p>
 */
@Slf4j
@Component
public class DorisConnectivityTestJobDefinitionBuilder implements ConnectivityTestJobDefinitionBuilder {

    @Resource
    private ConsoleSinkHoconBuilder consoleSinkHoconBuilder;

    @Resource
    private TestJobEnvConfigBuilder testJobEnvConfigBuilder;

    @Resource
    private SeaTunnelJobConfigAssembler seaTunnelJobConfigAssembler;

    @Override
    public boolean supports(DbType dbType) {
        return dbType == DbType.DORIS;
    }

    @Override
    public ConnectivityTestJob build(SeaTunnelClient client, DataSource datasource) {
        return build(client, datasource, "SOURCE", null, null);
    }

    @Override
    public ConnectivityTestJob build(
            SeaTunnelClient client,
            DataSource datasource,
            String role,
            DatasourceVerifyScope scope,
            String topic) {
        String connectionJson = datasource.getConnectionParams();

        DataSourceProcessor jdbcProcessor = DataSourceUtils.getDatasourceProcessor(DbType.JDBC);
        DataSourceHoconBuilder sourceBuilder = jdbcProcessor.getQueryBuilder("JDBC-JDBC");

        Config connectionConfig = buildJdbcProbeConnectionConfig(connectionJson);
        Config sourceNodeConfig = ConfigFactory.parseMap(Map.of(
                "sql", buildProbeSql(connectionConfig, scope),
                "readMode", "sql"
        ));
        HoconBuildContext buildContext = HoconBuildContext.builder()
                .connectionParam(connectionJson)
                .connectionConfig(connectionConfig)
                .nodeConfig(sourceNodeConfig)
                .stage(HoconBuildStage.INSTANCE)
                .build();
        Config sourcePluginConfig = sourceBuilder.buildSourceHocon(buildContext);

        String jobName = buildJobName(client.getId(), datasource.getId());
        String jobConfig = seaTunnelJobConfigAssembler.assemble(
                testJobEnvConfigBuilder.buildBatchEnv(),
                "Jdbc",
                sourcePluginConfig,
                consoleSinkHoconBuilder.pluginName(),
                consoleSinkHoconBuilder.build()
        );

        log.info("Doris 连通性测试使用 JDBC 探针: role={}, scope={}, sql={}",
                role, scope, sourceNodeConfig.getString("sql"));
        return new ConnectivityTestJob(jobName, jobConfig, "hocon", true);
    }

    private Config buildJdbcProbeConnectionConfig(String connectionJson) {
        Config original = ConfigFactory.parseString(connectionJson);
        Map<String, Object> values = new LinkedHashMap<>(original.root().unwrapped());

        if (!values.containsKey("user") && values.containsKey("username")) {
            values.put("user", values.get("username"));
        }
        Object password = values.get("password");
        if (password != null) {
            values.put("password", PasswordUtils.decodePassword(String.valueOf(password)));
        }
        return ConfigFactory.parseMap(values);
    }

    static String buildProbeSql(Config connectionConfig, DatasourceVerifyScope scope) {
        if (scope == null || !scope.hasTable()) {
            return "SELECT 1 AS connectivity_check";
        }

        String database = firstNonBlank(
                scope.getDatabase(),
                getString(connectionConfig, "database"));
        if (StringUtils.isBlank(database)) {
            throw new IllegalArgumentException(
                    "Doris 显式表连通性测试必须提供 database");
        }

        return "SELECT * FROM " + quoteIdentifier(database) + "."
                + quoteIdentifier(scope.getTable()) + " LIMIT 0";
    }

    private static String getString(Config config, String path) {
        if (config == null || !config.hasPath(path)) {
            return null;
        }
        return StringUtils.trimToNull(config.getString(path));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            throw new IllegalArgumentException("Doris identifier must not be blank");
        }
        return "`" + identifier.trim().replace("`", "``") + "`";
    }

    private String buildJobName(Long clientId, Long datasourceId) {
        return "connectivity_check_" + datasourceId + "_" + clientId + "_" + System.currentTimeMillis();
    }
}
