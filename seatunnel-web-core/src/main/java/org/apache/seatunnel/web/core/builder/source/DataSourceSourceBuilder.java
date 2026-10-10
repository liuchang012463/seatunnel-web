package org.apache.seatunnel.web.core.builder.source;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.utils.SqlValidator;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
    private DuckDbSourceInitSqlFileService duckDbSourceInitSqlFileService;

    @Value("${seatunnel.web.duckdb.driver-location:}")
    private String duckDbDriverLocation;

    /**
     * Optional directory holding the preinstalled DuckDB extensions on every engine node. The
     * generated init SQL disables auto-install, so without a preinstalled httpfs the LOAD fails.
     */
    @Value("${seatunnel.web.duckdb.extension-directory:}")
    private String duckDbExtensionDirectory;

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
                .engineVersion(dagContext.getEngineVersion())
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
        if (FILE_RESOURCE.equalsIgnoreCase(sourceMode)
                && "duckdb".equalsIgnoreCase(getFirstTrimmedString(
                resolveNodeConfig(data), "fileFormatType", "file_format_type"))) {
            // The engine registers no DuckDB plugin; the generated url/driver/query config is
            // served by the JDBC connector, whose factory identifier is "Jdbc".
            return "Jdbc";
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

        if ("duckdb".equalsIgnoreCase(configuredFormat)) {
            return buildDuckDbFileSource(reference, nodeConfig, resourceId);
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

    private Config buildDuckDbFileSource(FileResourceReference reference,
                                         Config nodeConfig,
                                         Long resourceId) {
        String objectKey = resolveFileResourcePath(reference);
        String normalizedKey = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        String normalizedKeyLower = normalizedKey.toLowerCase(Locale.ROOT);
        if (!normalizedKeyLower.endsWith(".db") && !normalizedKeyLower.endsWith(".duckdb")) {
            throw new IllegalArgumentException("DuckDB source requires a .db or .duckdb file resource");
        }
        if (!"STATIC".equalsIgnoreCase(reference.getCredentialMode())
                || StringUtils.isBlank(reference.getAccessKey())
                || StringUtils.isBlank(reference.getSecretKey())) {
            throw new IllegalArgumentException("DuckDB source requires static MinIO access credentials");
        }

        String tableName = getFirstTrimmedString(nodeConfig, "duckdbTable", "duckdb_table", "tableName");
        String schemaName = getFirstTrimmedString(nodeConfig, "duckdbSchema", "duckdb_schema");
        String readMode = getFirstTrimmedString(nodeConfig, "readMode", "read_mode");
        String sourceQuery;
        if ("sql".equalsIgnoreCase(readMode)) {
            sourceQuery = getFirstTrimmedString(nodeConfig, "sql", "query");
            if (StringUtils.isBlank(sourceQuery)) {
                throw new IllegalArgumentException("DuckDB custom SQL cannot be empty");
            }
            SqlValidator.validateSelectQuery(sourceQuery);
        } else {
            sourceQuery = "SELECT * FROM "
                    + quoteDuckDbIdentifier("duckdb_source")
                    + "."
                    + renderDuckDbTableName(schemaName, tableName);
        }
        DuckDbEndpoint endpoint = parseDuckDbEndpoint(reference.getEndpoint());
        String remoteDatabase = buildDuckDbObjectUri(reference.getBucket(), normalizedKey);
        String defaultSchema = StringUtils.defaultIfBlank(schemaName, "main");
        String extensionDirectory = resolveDuckDbExtensionDirectory();
        List<String> initSqlStatements = new ArrayList<>();
        initSqlStatements.add("SET autoinstall_known_extensions = false;");
        if (extensionDirectory != null) {
            initSqlStatements.add("SET extension_directory = " + sqlLiteral(extensionDirectory) + ";");
        }
        initSqlStatements.add("LOAD httpfs;");
        initSqlStatements.add("CREATE SECRET duckdb_source_minio_secret (");
        initSqlStatements.add("  TYPE s3,");
        initSqlStatements.add("  KEY_ID " + sqlLiteral(reference.getAccessKey()) + ",");
        initSqlStatements.add("  SECRET " + sqlLiteral(reference.getSecretKey()) + ",");
        initSqlStatements.add("  REGION " + sqlLiteral(reference.getRegion()) + ",");
        initSqlStatements.add("  ENDPOINT " + sqlLiteral(endpoint.hostAndPort()) + ",");
        initSqlStatements.add("  URL_STYLE " + sqlLiteral(reference.isPathStyleAccess() ? "path" : "vhost") + ",");
        initSqlStatements.add("  USE_SSL " + endpoint.ssl() + ",");
        initSqlStatements.add("  SCOPE " + sqlLiteral(remoteDatabase) + ",");
        initSqlStatements.add(");");
        initSqlStatements.add("ATTACH IF NOT EXISTS " + sqlLiteral(remoteDatabase)
                + " AS duckdb_source (READ_ONLY);");
        String initSql = String.join("\n", initSqlStatements);
        if (duckDbSourceInitSqlFileService == null) {
            throw new IllegalStateException("DuckDB source initialization SQL service is unavailable");
        }
        String initSqlPath = duckDbSourceInitSqlFileService.write(resourceId, initSql);
        String query = "USE " + quoteDuckDbIdentifier("duckdb_source")
                + "." + quoteDuckDbIdentifier(defaultSchema) + ";\n" + sourceQuery;

        Map<String, Object> properties = new HashMap<>();
        properties.put("autoinstall_known_extensions", "false");
        properties.put("threads", "2");
        properties.put("memory_limit", "512MB");
        properties.put("jdbc_stream_results", "true");

        Map<String, Object> source = new HashMap<>();
        source.put("url", "jdbc:duckdb:;session_init_sql_file=" + initSqlPath);
        source.put("driver", "org.duckdb.DuckDBDriver");
        source.put("driver_location", requireDuckDbDriverLocation());
        source.put("enable_concurrent_read", false);
        source.put("properties", properties);
        source.put("query", query);
        source.put("connection_check_timeout_sec", 30);
        if (nodeConfig.hasPath("plugin_output")) {
            source.put("plugin_output", nodeConfig.getString("plugin_output"));
        }
        return ConfigFactory.parseMap(source).resolve();
    }

    private String requireDuckDbDriverLocation() {
        String configuredPath = StringUtils.trimToEmpty(duckDbDriverLocation);
        if (configuredPath.isEmpty()) {
            throw new IllegalStateException(
                    "DuckDB source requires SEATUNNEL_WEB_DUCKDB_DRIVER_LOCATION to point to the JDBC JAR");
        }
        if (configuredPath.contains(";")
                || configuredPath.contains("\n")
                || configuredPath.contains("\r")) {
            throw new IllegalStateException("DuckDB JDBC driver location must be a single JAR path");
        }

        Path path;
        try {
            path = Path.of(configuredPath).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("DuckDB JDBC driver location is invalid", e);
        }
        if (!path.isAbsolute()
                || path.getFileName() == null
                || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalStateException(
                    "DuckDB JDBC driver location must be an absolute JAR path visible to SeaTunnel Engine nodes");
        }
        if (Files.exists(path) && (!Files.isRegularFile(path) || !Files.isReadable(path))) {
            throw new IllegalStateException(
                    "DuckDB JDBC driver location must be a readable JAR when it exists on the Web host");
        }
        return path.toString();
    }

    private String renderDuckDbTableName(String schemaName, String tableName) {
        if (StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("DuckDB source requires a table name");
        }
        String schema = StringUtils.trimToEmpty(schemaName);
        String table = tableName.trim();
        if (schema.isEmpty()) {
            String[] parts = table.split("\\.", -1);
            if (parts.length == 1) {
                schema = "main";
                table = parts[0];
            } else if (parts.length == 2) {
                schema = parts[0].trim();
                table = parts[1].trim();
            } else {
                throw new IllegalArgumentException("DuckDB table name must be table or schema.table");
            }
        }
        return quoteDuckDbIdentifier(schema) + "." + quoteDuckDbIdentifier(table);
    }

    private String quoteDuckDbIdentifier(String value) {
        String part = value.trim();
        if (part.isEmpty() || part.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("DuckDB table name contains an invalid identifier");
        }
        return "\"" + part.replace("\"", "\"\"") + "\"";
    }

    private DuckDbEndpoint parseDuckDbEndpoint(String endpoint) {
        String value = endpoint.trim();
        URI uri;
        try {
            uri = new URI(value.contains("://") ? value : "http://" + value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("MinIO endpoint is invalid for DuckDB S3 access", e);
        }
        String scheme = StringUtils.defaultString(uri.getScheme()).toLowerCase(Locale.ROOT);
        String authority = uri.getRawAuthority();
        String path = uri.getRawPath();
        if ((!"http".equals(scheme) && !"https".equals(scheme))
                || StringUtils.isBlank(authority)
                || uri.getRawUserInfo() != null
                || (StringUtils.isNotBlank(path) && !"/".equals(path))
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "MinIO endpoint for DuckDB must be an http(s) host and port without a path");
        }
        return new DuckDbEndpoint(authority, "https".equals(scheme));
    }

    private String buildDuckDbObjectUri(String bucket, String objectKey) {
        try {
            return new URI("s3", bucket, "/" + objectKey, null, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("DuckDB file resource path is invalid", e);
        }
    }

    private String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String resolveDuckDbExtensionDirectory() {
        String value = StringUtils.trimToNull(duckDbExtensionDirectory);
        if (value == null) {
            return null;
        }
        Path directory = Path.of(value).normalize();
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException("DuckDB extension directory must be an absolute path");
        }
        String path = directory.toString();
        if (path.contains(";") || path.contains("\n") || path.contains("\r")) {
            throw new IllegalArgumentException(
                    "DuckDB extension directory path must not contain semicolons or line breaks");
        }
        return path;
    }

    private record DuckDbEndpoint(String hostAndPort, boolean ssl) {
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
                .engineVersion(dagContext == null ? null : dagContext.getEngineVersion())
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
