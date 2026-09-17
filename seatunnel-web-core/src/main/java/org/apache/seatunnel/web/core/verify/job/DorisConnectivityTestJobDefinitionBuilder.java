package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.jdbc.HierarchicalJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.plugin.datasource.doris.metadata.DorisCatalog;
import org.apache.seatunnel.web.common.enums.HoconBuildStage;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.SeaTunnelClient;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Doris 连通性测试任务构建器。
 *
 * <p>Doris 不走 JDBC select 1 的方式测试连通性，而是通过 Doris Source 插件
 * 读取一张表来验证连通性。使用分区字段（分区表）或第一个字段（非分区表）
 * 构建 doris.filter.query 过滤条件，确保 Doris Source 能有效扫描数据。</p>
 *
 * <p>表名和字段信息通过 DorisCatalog（JDBC queryPort 9030）获取。
 * 数据湖投影故意不绑定默认 database（任务配置时再选库），此时先枚举可见库再选表探测。</p>
 */
@Slf4j
@Component
public class DorisConnectivityTestJobDefinitionBuilder implements ConnectivityTestJobDefinitionBuilder {

    private static final Set<String> SKIPPED_DATABASES = Set.of(
            "information_schema",
            "mysql",
            "_internal_schema",
            "sys");

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
        DbType dbType = datasource.getDbType();
        String connectionJson = datasource.getConnectionParams();

        // 通过 SPI 获取 Doris 处理器
        DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(dbType);
        BaseConnectionParam param =
                DataSourceUtils.buildJdbcConnectionParams(dbType, connectionJson);

        // 通过 DorisCatalog（JDBC queryPort）获取表和过滤字段
        JdbcCatalog catalog = processor.getMetadataService(param);
        DorisCatalog dorisCatalog = (DorisCatalog) catalog;
        ProbeTarget probe = resolveProbeTarget(dorisCatalog, param.getDatabase());
        String filterColumn = dorisCatalog.getFilterColumn(probe.database(), probe.table());

        // 构建 node 配置（database、table、doris.filter.query）
        Config sourceNodeConfig = buildConnectivitySourceNodeConfig(
                probe.database(), probe.table(), filterColumn);

        // 使用 DorisBatchBuilder 构建 source HOCON
        DataSourceHoconBuilder sourceBuilder = processor.getQueryBuilder("DORIS");
        Config connectionConfig = ConfigFactory.parseString(connectionJson);
        HoconBuildContext buildContext = HoconBuildContext.builder()
                .connectionParam(connectionJson)
                .connectionConfig(connectionConfig)
                .nodeConfig(sourceNodeConfig)
                .stage(HoconBuildStage.INSTANCE)
                .build();
        Config sourcePluginConfig = sourceBuilder.buildSourceHocon(buildContext);

        // 组装完整 job 配置
        String jobName = buildJobName(client.getId(), datasource.getId());
        String jobConfig = seaTunnelJobConfigAssembler.assemble(
                testJobEnvConfigBuilder.buildBatchEnv(),
                "Doris",
                sourcePluginConfig,
                consoleSinkHoconBuilder.pluginName(),
                consoleSinkHoconBuilder.build()
        );

        return new ConnectivityTestJob(jobName, jobConfig, "hocon", true);
    }

    /**
     * 解析连通性探测用的 library.table。
     *
     * <p>连接参数已绑定 database 时沿用该库；数据湖投影 database 为空时，
     * 在可见业务库中找第一张 BASE TABLE。</p>
     */
    static ProbeTarget resolveProbeTarget(HierarchicalJdbcCatalog catalog, String configuredDatabase) {
        if (StringUtils.isNotBlank(configuredDatabase)) {
            List<String> tables = catalog.listTables();
            if (tables == null || tables.isEmpty()) {
                throw new IllegalStateException(
                        "Doris 数据库 '" + configuredDatabase.trim()
                                + "' 中没有找到任何表，无法执行连通性测试");
            }
            return new ProbeTarget(configuredDatabase.trim(), tables.get(0));
        }

        List<OptionVO> databases = catalog.listDatabaseOptions();
        if (databases != null) {
            for (OptionVO databaseOption : databases) {
                String database = optionValue(databaseOption);
                if (StringUtils.isBlank(database) || isSkippedDatabase(database)) {
                    continue;
                }
                List<OptionVO> tables = catalog.listTableOptions(database);
                if (tables == null || tables.isEmpty()) {
                    continue;
                }
                String table = optionValue(tables.get(0));
                if (StringUtils.isNotBlank(table)) {
                    return new ProbeTarget(database, table);
                }
            }
        }

        throw new IllegalStateException(
                "Doris 集群中没有找到可用于连通性测试的业务表（连接未绑定默认库）");
    }

    private static boolean isSkippedDatabase(String database) {
        return SKIPPED_DATABASES.contains(database.toLowerCase(Locale.ROOT));
    }

    private static String optionValue(OptionVO option) {
        if (option == null || option.getValue() == null) {
            return null;
        }
        String value = option.getValue().toString();
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    /**
     * 构建连通性测试的 source node 配置。
     *
     * <p>包含 database、table 和 doris.filter.query。
     * doris.filter.query 使用分区字段（分区表）或第一个字段（非分区表）
     * 构建 IS NULL 过滤条件，确保 Doris Source 能有效扫描元数据路径。</p>
     */
    private Config buildConnectivitySourceNodeConfig(
            String database, String tableName, String filterColumn) {
        String filterQuery = "`" + filterColumn + "` IS NULL";
        log.info("Doris 连通性测试: table={}.{}, filterColumn={}, filterQuery={}",
                database, tableName, filterColumn, filterQuery);

        Map<String, Object> map = new LinkedHashMap<>(4);
        map.put("database", database);
        map.put("table", tableName);
        map.put("doris.filter.query", filterQuery);
        return ConfigFactory.parseMap(map);
    }

    private String buildJobName(Long clientId, Long datasourceId) {
        return "connectivity_check_" + datasourceId + "_" + clientId + "_" + System.currentTimeMillis();
    }

    record ProbeTarget(String database, String table) {
    }
}
