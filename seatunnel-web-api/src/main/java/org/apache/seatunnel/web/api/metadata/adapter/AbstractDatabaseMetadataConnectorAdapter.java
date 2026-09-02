package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.dao.entity.DataSource;

import java.net.URI;

abstract class AbstractDatabaseMetadataConnectorAdapter implements MetadataConnectorAdapter {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public JsonNode metadataPipelineRequest(String pipelineName, String serviceId, String serviceFqn) {
        return metadataPipelineRequestInternal(pipelineName, serviceId, serviceFqn, "0 0 1 1 *");
    }

    @Override
    public JsonNode metadataPipelineRequest(
            DataSource dataSource, String pipelineName, String serviceId, String serviceFqn) {
        return metadataPipelineRequestInternal(pipelineName, serviceId, serviceFqn, null);
    }

    private JsonNode metadataPipelineRequestInternal(
            String pipelineName, String serviceId, String serviceFqn, String scheduleInterval) {
        ObjectNode config = OBJECT_MAPPER.createObjectNode();
        config.put("type", "DatabaseMetadata");
        config.put("markDeletedTables", true);
        config.put("markDeletedSchemas", true);
        config.put("markDeletedDatabases", true);
        config.put("includeTables", true);
        config.put("includeViews", false);
        return pipelineRequest(pipelineName, serviceId, serviceFqn, "metadata", config, scheduleInterval);
    }

    @Override
    public JsonNode profilerPipelineRequest(String pipelineName, String serviceId, String serviceFqn) {
        return profilerPipelineRequest(pipelineName, serviceId, serviceFqn, null);
    }

    @Override
    public JsonNode profilerPipelineRequest(
            String pipelineName, String serviceId, String serviceFqn, String databaseFqn) {
        ObjectNode config = OBJECT_MAPPER.createObjectNode();
        config.put("type", "Profiler");
        config.set("databaseFilterPattern", filterPattern(databaseFqn));
        config.put("useFqnForFiltering", true);
        config.put("includeViews", false);
        config.put("computeMetrics", true);
        config.put("computeTableMetrics", true);
        config.put("computeColumnMetrics", true);
        config.put("profileSampleType", "PERCENTAGE");
        config.put("profileSample", 100);
        // The profiler is user-triggered only in this MVP.
        return pipelineRequest(pipelineName, serviceId, serviceFqn, "profiler", config, null);
    }

    protected ObjectNode baseServiceRequest(DataSource dataSource, String stableServiceName) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("name", stableServiceName);
        root.put("displayName", dataSource.getName());
        root.put("serviceType", openMetadataServiceType());
        return root;
    }

    protected ConnectionValues connectionValues(DataSource dataSource) {
        try {
            JsonNode source = OBJECT_MAPPER.readTree(dataSource.getConnectionParams());
            String jdbcUrl = text(source, "url");
            String username = firstNonBlank(text(source, "username"), text(source, "user"));
            String password = text(source, "password");
            // System-managed lake projections persist the password using the
            // shared datasource master key.  Ordinary historical datasource
            // rows may still contain a clear value; decodePassword preserves
            // those values while transparently handling the encrypted form.
            if (!isBlank(password)) {
                password = PasswordUtils.decodePassword(password);
            }
            String host = text(source, "host");
            String port = text(source, "port");
            String database = firstNonBlank(text(source, "database"), text(source, "databaseName"));
            if (jdbcUrl != null) {
                JdbcLocation jdbc = JdbcLocation.parse(jdbcUrl);
                host = firstNonBlank(host, jdbc.hostPort());
                database = firstNonBlank(database, jdbc.database());
            }
            if (host != null && port != null && !host.contains(":")) {
                host = host + ":" + port;
            }
            if (isBlank(host) || isBlank(username)) {
                throw invalidConnectionFailure();
            }
            return new ConnectionValues(host, database, username, password);
        } catch (MetadataIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw invalidConnectionFailure();
        }
    }

    private ObjectNode pipelineRequest(
            String pipelineName,
            String serviceId,
            String serviceFqn,
            String pipelineType,
            ObjectNode config,
            String scheduleInterval) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("name", pipelineName);
        root.put("displayName", pipelineName);
        ObjectNode service = root.putObject("service");
        service.put("id", serviceId);
        service.put("type", "databaseService");
        service.put("name", serviceFqn);
        service.put("fullyQualifiedName", serviceFqn);
        root.put("pipelineType", pipelineType);
        root.putObject("sourceConfig").set("config", config);
        ObjectNode airflow = root.putObject("airflowConfig");
        // Exploration is explicitly user-triggered in this product. Keep the
        // pipeline unpaused so a managed trigger can execute, but omit a cron
        // expression so Airflow cannot create a schedule behind the operator's
        // back.  A paused DAG accepts a trigger in 1.12.10 but leaves the run
        // permanently queued.
        airflow.put("pausePipeline", false);
        airflow.put("concurrency", 1);
        if (scheduleInterval == null || scheduleInterval.isBlank()) {
            airflow.putNull("scheduleInterval");
        } else {
            airflow.put("scheduleInterval", scheduleInterval);
        }
        airflow.put("pipelineCatchup", false);
        airflow.put("maxActiveRuns", 1);
        airflow.put("retries", 0);
        airflow.put("retryDelay", 300);
        root.put("loggerLevel", "INFO");
        root.put("raiseOnError", true);
        return root;
    }

    protected ObjectNode filterPattern() {
        return filterPattern(null);
    }

    protected ObjectNode filterPattern(String databaseFqn) {
        ObjectNode filter = OBJECT_MAPPER.createObjectNode();
        if (isBlank(databaseFqn)) {
            filter.set("includes", OBJECT_MAPPER.createArrayNode());
        } else {
            // OpenMetadata ingestion uses Python regular expressions, so avoid Java-only Pattern.quote syntax.
            filter.withArray("includes").add("^" + pythonRegexLiteral(databaseFqn) + "$");
        }
        filter.set("excludes", OBJECT_MAPPER.createArrayNode());
        return filter;
    }

    private static String pythonRegexLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ("\\.^$|?*+()[]{}".indexOf(ch) >= 0) {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }

    protected ObjectNode passwordAuth(String password) {
        if (isBlank(password)) {
            throw invalidConnectionFailure();
        }
        ObjectNode auth = OBJECT_MAPPER.createObjectNode();
        auth.put("password", password);
        return auth;
    }

    private static String text(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    protected static MetadataIntegrationException invalidConnectionFailure() {
        return new MetadataIntegrationException(
                MetadataErrorCode.SOURCE_CONNECTION_ERROR,
                "Data source connection cannot be converted to the OpenMetadata 1.12.10 schema");
    }

    /** Returns the persisted SeaTunnel connection JSON for connector-specific options. */
    protected JsonNode rawConnection(DataSource dataSource) {
        try {
            return OBJECT_MAPPER.readTree(dataSource.getConnectionParams());
        } catch (Exception error) {
            throw invalidConnectionFailure();
        }
    }

    protected String connectionText(JsonNode source, String field) {
        return text(source, field);
    }

    protected record ConnectionValues(String hostPort, String database, String username, String password) {
    }

    private record JdbcLocation(String hostPort, String database) {
        private static JdbcLocation parse(String jdbcUrl) {
            String normalized = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
            int schemeEnd = normalized.indexOf("://");
            if (schemeEnd < 0) {
                return new JdbcLocation(null, null);
            }
            URI uri = URI.create("placeholder" + normalized.substring(schemeEnd));
            String authority = uri.getRawAuthority();
            String path = uri.getPath();
            String database = path == null ? null : path.replaceFirst("^/", "");
            return new JdbcLocation(authority, database);
        }
    }
}
