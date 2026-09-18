package org.apache.seatunnel.web.core.builder.source;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.web.common.config.ConfigValidator;
import org.apache.seatunnel.web.common.config.ReadonlyConfig;
import org.apache.seatunnel.web.common.enums.HoconBuildStage;
import org.apache.seatunnel.web.core.builder.context.DagBuildContext;
import org.apache.seatunnel.web.core.fileupload.BuiltInMinioProperties;
import org.apache.seatunnel.web.core.fileresource.FileResourceReference;
import org.apache.seatunnel.web.core.fileresource.FileResourceResolver;
import org.apache.seatunnel.web.core.job.handler.single.LocalFileSourceValidator;
import org.apache.seatunnel.web.core.job.validation.DorisTaskScopeValidator;
import org.apache.seatunnel.web.core.time.TimeVariableJdbcSqlRenderService;
import org.apache.seatunnel.web.core.time.IncrementalSqlRenderer;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DataSourceSourceBuilder implements SourceNodeConfigBuilder {

    private static final String NODE_TYPE = "source";

    private static final String KEY_DATA_SOURCE_ID = "dataSourceId";
    private static final String KEY_DB_TYPE = "dbType";
    private static final String KEY_PLUGIN_NAME = "pluginName";
    private static final String KEY_CONNECTOR_TYPE = "connectorType";
    private static final String KEY_SOURCE_MODE = "sourceMode";
    private static final String WEB_UPLOAD = "WEB_UPLOAD";
    private static final String FILE_RESOURCE = "FILE_RESOURCE";

    private static final String KEY_SQL = "sql";
    private static final String KEY_WHERE_CONDITION = "where_condition";

    @Resource
    private DataSourceDao dataSourceDao;

    @Resource
    private TimeVariableJdbcSqlRenderService timeVariableJdbcSqlRenderService;

    @Resource
    private BuiltInMinioProperties builtInMinioProperties;

    @Resource
    private FileResourceResolver fileResourceResolver;

    @Resource
    private DorisTaskScopeValidator dorisTaskScopeValidator;

    @Override
    public String nodeType() {
        return NODE_TYPE;
    }

    @Override
    public Config build(Config data) {
        return build(data, DagBuildContext.empty());
    }

    @Override
    public Config build(Config data, DagBuildContext dagContext) {
        Config nodeConfig = resolveNodeConfig(data);
        nodeConfig = appendPluginOutputIfNecessary(data, nodeConfig, dagContext);

        String sourceMode = getTrimmedString(nodeConfig, KEY_SOURCE_MODE);
        if (FILE_RESOURCE.equalsIgnoreCase(sourceMode)) {
            return buildFileResourceSource(nodeConfig, dagContext);
        }
        if (WEB_UPLOAD.equalsIgnoreCase(sourceMode)) {
            return buildWebUploadSource(nodeConfig, dagContext);
        }

        Long dataSourceId = parseDataSourceId(nodeConfig);
        DataSource dataSource = getRequiredDataSource(dataSourceId);

        DbType dbType = parseDbType(nodeConfig);
        String pluginName = getRequiredPluginName(nodeConfig);

        validateDorisSourceScope(nodeConfig, dataSourceId, dbType);

        DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(dbType);
        DataSourceHoconBuilder hoconBuilder = processor.getQueryBuilder(pluginName);

        if (!hoconBuilder.supportsSource()) {
            throw new IllegalArgumentException(pluginName + " does not support source side");
        }

        nodeConfig = IncrementalSqlRenderer.render(nodeConfig, dagContext.getScheduleConfig());
        nodeConfig = renderTimeVariablesIfNecessary(
                nodeConfig,
                hoconBuilder,
                dagContext.getScheduleConfig()
        );

        Config connectionConfig = ConfigFactory.parseString(dataSource.getConnectionParams());

        HoconBuildContext buildContext = HoconBuildContext.builder()
                .connectionParam(dataSource.getConnectionParams())
                .connectionConfig(connectionConfig)
                .nodeConfig(nodeConfig)
                .scheduleConfig(dagContext.getScheduleConfig())
                .stage(HoconBuildStage.INSTANCE)
                .build();

        Config sourceConfig = hoconBuilder.buildSourceHocon(buildContext);

        validateSourceConfig(processor, pluginName, sourceConfig);

        return sourceConfig;
    }

    private void validateDorisSourceScope(Config config, Long dataSourceId, DbType dbType) {
        if (dbType != DbType.DORIS || dorisTaskScopeValidator == null) {
            return;
        }

        List<String> tables = new ArrayList<>();
        String table = getFirstTrimmedString(config, "table", "table_path");
        if (StringUtils.isNotBlank(table) && !table.contains("${")) {
            tables.add(table);
        }
        if (config.hasPath("table_list")) {
            try {
                for (ConfigValue value : config.getList("table_list")) {
                    Object unwrapped = value.unwrapped();
                    if (unwrapped instanceof String tableName
                            && StringUtils.isNotBlank(tableName)
                            && !tableName.contains("${")) {
                        tables.add(tableName.trim());
                    } else if (unwrapped instanceof Map<?, ?> tableConfig) {
                        Object tableValue = tableConfig.get("table");
                        if (tableValue != null && StringUtils.isNotBlank(String.valueOf(tableValue))
                                && !String.valueOf(tableValue).contains("${")) {
                            tables.add(String.valueOf(tableValue).trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                // Connector-specific pattern/list forms are validated by the
                // connector itself; an explicit scalar table is handled above.
            }
        }

        dorisTaskScopeValidator.validate(
                String.valueOf(dataSourceId),
                getTrimmedString(config, "database"),
                tables,
                !tables.isEmpty());
    }

    private Config renderTimeVariablesIfNecessary(Config config,
                                                  DataSourceHoconBuilder hoconBuilder,
                                                  JobScheduleConfig scheduleConfig) {
        /*
         * 这里先只处理 JDBC SQL 类场景。
         * CDC 一般不需要渲染 sql / where_condition。
         */
        Map<String, Object> extra = new HashMap<>();

        renderSqlFragmentIfNecessary(config, hoconBuilder, scheduleConfig, KEY_SQL, extra);
        renderSqlFragmentIfNecessary(config, hoconBuilder, scheduleConfig, KEY_WHERE_CONDITION, extra);

        if (extra.isEmpty()) {
            return config;
        }

        return ConfigFactory.parseMap(extra)
                .withFallback(config)
                .resolve();
    }

    private void renderSqlFragmentIfNecessary(Config config,
                                              DataSourceHoconBuilder hoconBuilder,
                                              JobScheduleConfig scheduleConfig,
                                              String key,
                                              Map<String, Object> extra) {
        String value = getTrimmedString(config, key);
        if (StringUtils.isBlank(value)) {
            return;
        }

        String renderedValue = timeVariableJdbcSqlRenderService.renderSql(
                value,
                hoconBuilder,
                scheduleConfig
        );

        extra.put(key, renderedValue);
    }

    private Config appendPluginOutputIfNecessary(Config data,
                                                 Config config,
                                                 DagBuildContext context) {
        if (context == null || !context.hasTransform()) {
            return config;
        }

        String pluginOutput = getTrimmedString(config, "pluginOutput");
        if (StringUtils.isBlank(pluginOutput)) {
            pluginOutput = getTrimmedString(data, "pluginOutput");
        }

        if (StringUtils.isBlank(pluginOutput)) {
            return config;
        }

        Map<String, Object> extra = new HashMap<>();
        extra.put("plugin_output", pluginOutput);

        return ConfigFactory.parseMap(extra).withFallback(config).resolve();
    }

    @Override
    public String connectorName(Config data) {
        Config nodeConfig = resolveNodeConfig(data);
        String sourceMode = getFirstTrimmedString(nodeConfig, KEY_SOURCE_MODE);
        if (StringUtils.isBlank(sourceMode)) {
            sourceMode = getTrimmedString(data, KEY_SOURCE_MODE);
        }
        if (WEB_UPLOAD.equalsIgnoreCase(sourceMode)
                || FILE_RESOURCE.equalsIgnoreCase(sourceMode)) {
            return "S3File";
        }

        String dbTypeValue = getFirstTrimmedString(nodeConfig, KEY_DB_TYPE);
        if (StringUtils.isBlank(dbTypeValue)) {
            dbTypeValue = getTrimmedString(data, KEY_DB_TYPE);
        }
        if ("DORIS".equalsIgnoreCase(dbTypeValue)) {
            return "Doris";
        }

        String connectorType = getFirstTrimmedString(nodeConfig, KEY_CONNECTOR_TYPE);
        if (StringUtils.isBlank(connectorType)) {
            connectorType = getTrimmedString(data, KEY_CONNECTOR_TYPE);
        }
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
                    "Missing required field '" + KEY_DATA_SOURCE_ID + "' in source node config");
        }

        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid '" + KEY_DATA_SOURCE_ID + "': " + value + ", expected numeric value", e);
        }
    }

    private Config buildFileResourceSource(Config nodeConfig, DagBuildContext dagContext) {
        Map<String, Object> nodeValues = new HashMap<>(nodeConfig.root().unwrapped());
        Long resourceId = LocalFileSourceValidator.requireFileResourceId(nodeValues);
        String configuredFormat = getFirstTrimmedString(
                nodeConfig, "fileFormatType", "file_format_type");
        boolean binarySource = isBinaryFileResource(nodeConfig, configuredFormat);
        if (binarySource) {
            LocalFileSourceValidator.validateBinaryFileResource(nodeValues);
        } else {
            LocalFileSourceValidator.validateFileResource(nodeValues);
        }
        if (fileResourceResolver == null) {
            throw new IllegalStateException("File resource resolver is not configured");
        }

        FileResourceReference reference = fileResourceResolver.resolve(resourceId);
        if (reference == null) {
            throw new IllegalArgumentException(
                    "File resource does not exist or is not available, fileResourceId=" + resourceId);
        }

        Map<String, Object> connection = new HashMap<>();
        connection.put("dbType", hoconDbType(reference));
        connection.put("endpoint", reference.getEndpoint());
        connection.put("region", reference.getRegion());
        connection.put("bucket", reference.getBucket());
        connection.put("basePath", normalizeObjectPath(reference.getBasePath(), "basePath"));
        connection.put("credentialMode", reference.getCredentialMode());
        putIfNotBlank(connection, "accessKey", reference.getAccessKey());
        putIfNotBlank(connection, "secretKey", reference.getSecretKey());
        connection.put("pathStyleAccess", reference.isPathStyleAccess());

        String fileFormatType = binarySource
                ? "binary"
                : requireProperty(configuredFormat, "fileFormatType").toLowerCase(Locale.ROOT);
        Map<String, Object> pathOverride = new HashMap<>();
        pathOverride.put("path", resolveFileResourcePath(reference));
        pathOverride.put("fileFormatType", fileFormatType);
        if (binarySource) {
            if (!nodeConfig.hasPath("binaryChunkSize")) {
                pathOverride.put("binaryChunkSize", 1048576);
            }
            if (!nodeConfig.hasPath("binaryCompleteFileMode")) {
                pathOverride.put("binaryCompleteFileMode", false);
            }
        }
        Config effectiveNodeConfig = ConfigFactory.parseMap(pathOverride)
                .withFallback(nodeConfig)
                .resolve();

        return buildS3FileSource(
                ConfigFactory.parseMap(connection).resolve(),
                effectiveNodeConfig,
                dagContext,
                reference.getProviderType());
    }

    private boolean isBinaryFileResource(Config nodeConfig, String fileFormatType) {
        return "binary".equalsIgnoreCase(fileFormatType)
                || (StringUtils.isBlank(fileFormatType)
                && "resource".equalsIgnoreCase(getTrimmedString(nodeConfig, "readMode")));
    }

    private Config buildWebUploadSource(Config nodeConfig, DagBuildContext dagContext) {
        String jobDefinitionIdText = getTrimmedString(nodeConfig, "jobDefinitionId");
        String sessionId = getTrimmedString(nodeConfig, "uploadSessionId");
        if (StringUtils.isBlank(jobDefinitionIdText) || StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException(
                    "WEB_UPLOAD source requires jobDefinitionId and uploadSessionId");
        }

        final Long jobDefinitionId;
        try {
            jobDefinitionId = Long.valueOf(jobDefinitionIdText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid jobDefinitionId for WEB_UPLOAD source", e);
        }

        Map<String, Object> connection = new HashMap<>();
        connection.put("dbType", "MINIO");
        connection.put("endpoint", requireProperty(
                builtInMinioProperties.getRuntimeEndpoint(),
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_ENDPOINT"));
        connection.put("region", "us-east-1");
        connection.put("bucket", requireProperty(
                builtInMinioProperties.getBucket(),
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_BUCKET"));
        connection.put("basePath", "/");
        connection.put("credentialMode", "STATIC");
        connection.put("accessKey", requireProperty(
                builtInMinioProperties.getRuntimeAccessKey(),
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_ACCESS_KEY"));
        connection.put("secretKey", requireProperty(
                builtInMinioProperties.getRuntimeSecretKey(),
                "SEATUNNEL_WEB_FILE_UPLOAD_MINIO_RUNTIME_SECRET_KEY"));
        connection.put("pathStyleAccess", true);

        Map<String, Object> node = new HashMap<>();
        String configuredPath = getTrimmedString(nodeConfig, "path");
        node.put("path", StringUtils.defaultIfBlank(
                configuredPath, builtInMinioProperties.objectPath(jobDefinitionId, sessionId)));
        String fileFormatType = getFirstTrimmedString(
                nodeConfig, "fileFormatType", "file_format_type");
        if (StringUtils.isBlank(fileFormatType) || "binary".equalsIgnoreCase(fileFormatType)) {
            node.put("binaryChunkSize", 1048576);
            node.put("binaryCompleteFileMode", false);
        }
        Config effectiveNodeConfig = ConfigFactory.parseMap(node)
                .withFallback(nodeConfig)
                .resolve();
        Config connectionConfig = ConfigFactory.parseMap(connection).resolve();

        return buildS3FileSource(connectionConfig, effectiveNodeConfig, dagContext, "MINIO");
    }

    private Config buildS3FileSource(Config connectionConfig,
                                     Config nodeConfig,
                                     DagBuildContext dagContext,
                                     String providerType) {
        DataSourceProcessor processor = getS3FileProcessor(providerType);
        if (processor == null) {
            throw new IllegalArgumentException("S3File datasource processor is unavailable");
        }
        DataSourceHoconBuilder hoconBuilder = processor.getQueryBuilder("S3File");
        if (hoconBuilder == null || !hoconBuilder.supportsSource()) {
            throw new IllegalArgumentException("S3File does not support source side");
        }

        nodeConfig = IncrementalSqlRenderer.render(
                nodeConfig, dagContext == null ? null : dagContext.getScheduleConfig());
        nodeConfig = renderTimeVariablesIfNecessary(
                nodeConfig, hoconBuilder,
                dagContext == null ? null : dagContext.getScheduleConfig());

        HoconBuildContext buildContext = HoconBuildContext.builder()
                .connectionParam(connectionConfig.root().render())
                .connectionConfig(connectionConfig)
                .nodeConfig(nodeConfig)
                .scheduleConfig(dagContext == null ? null : dagContext.getScheduleConfig())
                .stage(HoconBuildStage.INSTANCE)
                .build();

        Config sourceConfig = hoconBuilder.buildSourceHocon(buildContext);
        validateSourceConfig(processor, "S3File", sourceConfig);
        return sourceConfig;
    }

    private DataSourceProcessor getS3FileProcessor(String providerType) {
        DbType preferredType = "MINIO".equalsIgnoreCase(providerType) ? DbType.MINIO : DbType.S3;
        DbType fallbackType = preferredType == DbType.MINIO ? DbType.S3 : DbType.MINIO;
        IllegalArgumentException preferredUnavailable = null;
        try {
            DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(preferredType);
            if (processor != null) {
                return processor;
            }
        } catch (IllegalArgumentException unavailable) {
            preferredUnavailable = unavailable;
        }
        try {
            DataSourceProcessor processor = DataSourceUtils.getDatasourceProcessor(fallbackType);
            if (processor != null) {
                return processor;
            }
        } catch (IllegalArgumentException fallbackUnavailable) {
            if (preferredUnavailable == null) {
                preferredUnavailable = fallbackUnavailable;
            } else {
                preferredUnavailable.addSuppressed(fallbackUnavailable);
            }
        }
        throw new IllegalArgumentException(
                "S3File datasource processor is unavailable", preferredUnavailable);
    }

    private String hoconDbType(FileResourceReference reference) {
        return "MINIO".equalsIgnoreCase(reference.getProviderType()) ? "MINIO" : "S3";
    }

    private String resolveFileResourcePath(FileResourceReference reference) {
        String basePath = normalizeObjectPath(reference.getBasePath(), "basePath");
        String objectKey = requireProperty(reference.getObjectKey(), "objectKey");
        if (objectKey.contains("\\")) {
            throw new IllegalArgumentException("File resource objectKey must not contain backslashes");
        }

        boolean absoluteObjectKey = objectKey.startsWith("/");
        String path = normalizeObjectPath(
                absoluteObjectKey ? objectKey : "/" + objectKey,
                "objectKey");
        if (!isWithinBase(basePath, path) && !absoluteObjectKey) {
            path = normalizeObjectPath(
                    "/".equals(basePath) ? "/" + objectKey : basePath + "/" + objectKey,
                    "objectKey");
        }
        if (!isWithinBase(basePath, path)) {
            throw new IllegalArgumentException(
                    "File resource objectKey is outside its storage basePath: " + objectKey);
        }
        return path;
    }

    private String normalizeObjectPath(String value, String fieldName) {
        String path = requireProperty(value, fieldName);
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.contains("\\")) {
            throw new IllegalArgumentException(fieldName + " must not contain backslashes");
        }

        StringBuilder normalized = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException(fieldName + " must not contain '..'");
            }
            normalized.append('/').append(part);
        }
        return normalized.length() == 0 ? "/" : normalized.toString();
    }

    private boolean isWithinBase(String basePath, String path) {
        return "/".equals(basePath)
                || path.equals(basePath)
                || path.startsWith(basePath + "/");
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value.trim());
        }
    }

    private String requireProperty(String value, String propertyName) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Missing required property: " + propertyName);
        }
        return value.trim();
    }

    private DataSource getRequiredDataSource(Long dataSourceId) {
        DataSource dataSource = dataSourceDao.queryById(dataSourceId);
        if (dataSource == null) {
            throw new IllegalArgumentException(
                    "Source data source does not exist, dataSourceId=" + dataSourceId);
        }
        return dataSource;
    }

    private DbType parseDbType(Config config) {
        String dbTypeValue = getTrimmedString(config, KEY_DB_TYPE);
        if (StringUtils.isBlank(dbTypeValue)) {
            throw new IllegalArgumentException(
                    "Missing required field '" + KEY_DB_TYPE + "' in source node config");
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
                    "Missing required field '" + KEY_PLUGIN_NAME + "' in source node config");
        }
        return pluginName.toUpperCase();
    }

    private void validateSourceConfig(DataSourceProcessor processor,
                                      String pluginName,
                                      Config sourceConfig) {
        ConfigValidator.of(ReadonlyConfig.fromConfig(sourceConfig))
                .validate(processor.sourceOptionRule(pluginName));
    }

    private String getTrimmedString(Config config, String path) {
        if (config == null || !config.hasPath(path)) {
            return null;
        }

        String value = config.getString(path);
        return value == null ? null : value.trim();
    }

    private String getFirstTrimmedString(Config config, String... paths) {
        for (String path : paths) {
            String value = getTrimmedString(config, path);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
