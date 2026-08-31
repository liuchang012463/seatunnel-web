package org.apache.seatunnel.web.core.builder.sink;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.common.config.ConfigValidator;
import org.apache.seatunnel.web.common.config.ReadonlyConfig;
import org.apache.seatunnel.web.common.enums.HoconBuildStage;
import org.apache.seatunnel.web.core.builder.context.DagBuildContext;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * DataSource sink node builder.
 *
 * <p>
 * Although the current main implementation is JDBC Sink,
 * this builder is designed to support other sink plugin types later,
 * such as Kafka, File, Hive, StarRocks, Doris, etc.
 * </p>
 */
@Component
public class DataSourceSinkBuilder implements SinkNodeConfigBuilder {

    private static final String NODE_TYPE = "sink";

    private static final String KEY_DATA_SOURCE_ID = "dataSourceId";
    private static final String KEY_DB_TYPE = "dbType";
    private static final String KEY_PLUGIN_NAME = "pluginName";
    private static final String KEY_CONNECTOR_TYPE = "connectorType";

    @Resource
    private DataSourceDao dataSourceDao;

    @Resource
    private LakeOdsDatabaseBindingDao lakeOdsDatabaseBindingDao;

    @Resource
    private Environment environment;

    @Override
    public String nodeType() {
        return NODE_TYPE;
    }

    @Override
    public Config build(Config data) {
        return build(data, DagBuildContext.empty());
    }

    @Override
    public Config build(Config data, DagBuildContext context) {
        Config config = resolveNodeConfig(data);
        config = appendPluginInputIfNecessary(data, config, context);

        Long dataSourceId = parseDataSourceId(config);
        DataSource dataSource = getRequiredDataSource(dataSourceId);

        DbType dbType = parseDbType(data);
        String pluginName = getRequiredPluginName(data);

        config = overrideLakeDatabase(config, context, dataSourceId, dataSource, dbType, pluginName);

        DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(dbType);
        DataSourceHoconBuilder hoconBuilder = processor.getQueryBuilder(pluginName);

        if (!hoconBuilder.supportsSink()) {
            throw new IllegalArgumentException(pluginName + " does not support sink side");
        }

        HoconBuildContext buildContext = buildHoconContext(
                dataSource,
                config,
                context
        );

        Config sinkConfig = hoconBuilder.buildSinkHocon(buildContext);

        validateSinkConfig(processor, pluginName, sinkConfig);

        return sinkConfig;
    }

    /**
     * Apply the server-owned ODS database to a Doris sink.  The raw node
     * database is intentionally only a fallback for ordinary non-lake jobs.
     */
    private Config overrideLakeDatabase(Config config,
                                        DagBuildContext context,
                                        Long sinkDataSourceId,
                                        DataSource sinkDataSource,
                                        DbType dbType,
                                        String pluginName) {
        Long bindingId = context == null ? null : context.getOdsDatabaseBindingId();
        if (bindingId == null) {
            bindingId = parseOptionalLong(config, "odsDatabaseBindingId");
            if (bindingId == null) {
                bindingId = parseOptionalLong(config, "ods_database_binding_id");
            }
        }
        if (bindingId == null) {
            return config;
        }
        if (bindingId <= 0) {
            throw new IllegalArgumentException("odsDatabaseBindingId must be positive");
        }

        if (dbType != DbType.DORIS || sinkDataSource == null
                || sinkDataSource.getDbType() != DbType.DORIS
                || !"DORIS".equalsIgnoreCase(pluginName)) {
            throw new IllegalArgumentException(
                    "odsDatabaseBindingId requires the configured Doris sink data source");
        }
        if (lakeOdsDatabaseBindingDao == null) {
            throw new IllegalStateException("lake ODS database binding resolver is not configured");
        }

        LakeOdsDatabaseBinding binding = lakeOdsDatabaseBindingDao.queryActiveById(bindingId);
        if (binding == null
                || binding.getResourceStatus() == null
                || !"READY".equals(binding.getResourceStatus().getCode())
                || StringUtils.isBlank(binding.getDatabaseName())) {
            throw new IllegalArgumentException(
                    "ODS database binding is not active and READY, bindingId=" + bindingId);
        }
        if (!sinkDataSourceId.equals(binding.getLakeDataSourceId())) {
            throw new IllegalArgumentException(
                    "ODS database binding does not belong to the configured Doris sink, bindingId="
                            + bindingId);
        }

        Long configuredLakeDataSourceId = configuredLakeDataSourceId();
        if (!configuredLakeDataSourceId.equals(binding.getLakeDataSourceId())) {
            throw new IllegalArgumentException(
                    "ODS database binding does not belong to the configured Lake Doris data source, "
                            + "bindingId=" + bindingId);
        }

        Map<String, Object> override = new HashMap<>(1);
        override.put("database", binding.getDatabaseName().trim());
        return ConfigFactory.parseMap(override)
                .withFallback(config)
                .resolve();
    }

    private Long configuredLakeDataSourceId() {
        String value = environment == null
                ? null
                : environment.getProperty("seatunnel.lake.data-source-id");
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Lake Doris data source is not configured");
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid configured Lake Doris data source id", e);
        }
    }

    private HoconBuildContext buildHoconContext(DataSource dataSource,
                                                Config nodeConfig,
                                                DagBuildContext dagContext) {
        String connectionParam = dataSource.getConnectionParams();
        Config connectionConfig = ConfigFactory.parseString(connectionParam);

        return HoconBuildContext.builder()
                .connectionParam(connectionParam)
                .connectionConfig(connectionConfig)
                .nodeConfig(nodeConfig)
                .scheduleConfig(dagContext == null ? null : dagContext.getScheduleConfig())
                .stage(HoconBuildStage.INSTANCE)
                .build();
    }

    private Config appendPluginInputIfNecessary(Config data,
                                                Config config,
                                                DagBuildContext context) {
        if (context == null || !context.hasTransform()) {
            return config;
        }

        String pluginInput = getTrimmedString(config, "pluginInput");
        if (StringUtils.isBlank(pluginInput)) {
            pluginInput = getTrimmedString(data, "pluginInput");
        }

        if (StringUtils.isBlank(pluginInput)) {
            return config;
        }

        Map<String, Object> extra = new HashMap<String, Object>();
        extra.put("plugin_input", pluginInput);

        return ConfigFactory.parseMap(extra)
                .withFallback(config)
                .resolve();
    }

    @Override
    public String connectorName(Config data) {
        String dbTypeValue = getTrimmedString(data, KEY_DB_TYPE);
        if ("DORIS".equalsIgnoreCase(dbTypeValue)) {
            return "Doris";
        }

        String connectorType = getTrimmedString(data, KEY_CONNECTOR_TYPE);
        if (StringUtils.isNotBlank(connectorType)) {
            return connectorType;
        }

        throw new IllegalArgumentException(
                "Missing connector name, field '" + KEY_CONNECTOR_TYPE + "' is not provided");
    }

    private Long parseDataSourceId(Config config) {
        String value = getTrimmedString(config, KEY_DATA_SOURCE_ID);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(
                    "Missing required field '" + KEY_DATA_SOURCE_ID + "' in sink node config");
        }

        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid '" + KEY_DATA_SOURCE_ID + "': " + value + ", expected numeric value", e);
        }
    }

    private DataSource getRequiredDataSource(Long dataSourceId) {
        DataSource dataSource = dataSourceDao.queryById(dataSourceId);
        if (dataSource == null) {
            throw new IllegalArgumentException(
                    "Sink data source does not exist, dataSourceId=" + dataSourceId);
        }
        return dataSource;
    }

    private DbType parseDbType(Config config) {
        String dbTypeValue = getTrimmedString(config, KEY_DB_TYPE);
        if (StringUtils.isBlank(dbTypeValue)) {
            throw new IllegalArgumentException(
                    "Missing required field '" + KEY_DB_TYPE + "' in sink node config");
        }

        try {
            return DbType.valueOf(dbTypeValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported dbType: " + dbTypeValue, e);
        }
    }

    private String getRequiredPluginName(Config config) {
        String pluginName = getTrimmedString(config, KEY_PLUGIN_NAME);
        if (StringUtils.isBlank(pluginName)) {
            throw new IllegalArgumentException(
                    "Missing required field '" + KEY_PLUGIN_NAME + "' in sink node config");
        }
        return pluginName.toUpperCase();
    }

    private void validateSinkConfig(DataSourceProcessor processor,
                                    String pluginName,
                                    Config sinkConfig) {
        ConfigValidator.of(ReadonlyConfig.fromConfig(sinkConfig))
                .validate(processor.sinkOptionRule(pluginName));
    }

    private String getTrimmedString(Config config, String path) {
        if (config == null || !config.hasPath(path)) {
            return null;
        }

        String value = config.getString(path);
        return value == null ? null : value.trim();
    }

    private Long parseOptionalLong(Config config, String path) {
        if (config == null || !config.hasPath(path)) {
            return null;
        }
        Object value = config.getAnyRef(path);
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.longValue()
                    : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid '" + path + "': " + value, e);
        }
    }
}
