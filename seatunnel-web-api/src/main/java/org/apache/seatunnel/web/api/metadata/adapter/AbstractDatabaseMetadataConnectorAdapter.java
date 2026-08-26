package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.dao.entity.DataSource;

import java.net.URI;

abstract class AbstractDatabaseMetadataConnectorAdapter implements MetadataConnectorAdapter {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public JsonNode metadataPipelineRequest(String pipelineName, String serviceId, String serviceFqn) {
        ObjectNode config = OBJECT_MAPPER.createObjectNode();
        config.put("type", "DatabaseMetadata");
        config.put("markDeletedTables", true);
        config.put("markDeletedSchemas", true);
        config.put("markDeletedDatabases", true);
        config.put("includeTables", true);
        config.put("includeViews", false);
        return pipelineRequest(pipelineName, serviceId, serviceFqn, "metadata", config);
    }

    @Override
    public JsonNode profilerPipelineRequest(String pipelineName, String serviceId, String serviceFqn) {
        ObjectNode config = OBJECT_MAPPER.createObjectNode();
        config.put("type", "Profiler");
        config.set("databaseFilterPattern", filterPattern());
        config.put("includeViews", false);
        config.put("computeMetrics", true);
        config.put("computeTableMetrics", true);
        config.put("computeColumnMetrics", true);
        config.put("profileSampleType", "PERCENTAGE");
        config.put("profileSample", 100);
        return pipelineRequest(pipelineName, serviceId, serviceFqn, "profiler", config);
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
                throw invalidConnection();
            }
            return new ConnectionValues(host, database, username, password);
        } catch (MetadataIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw invalidConnection();
        }
    }

    private ObjectNode pipelineRequest(
            String pipelineName, String serviceId, String serviceFqn, String pipelineType, ObjectNode config) {
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
        airflow.put("pausePipeline", false);
        airflow.put("concurrency", 1);
        airflow.put("scheduleInterval", "0 0 1 1 *");
        airflow.put("pipelineCatchup", false);
        airflow.put("maxActiveRuns", 1);
        airflow.put("retries", 0);
        airflow.put("retryDelay", 300);
        root.put("loggerLevel", "INFO");
        root.put("raiseOnError", true);
        return root;
    }

    protected ObjectNode filterPattern() {
        ObjectNode filter = OBJECT_MAPPER.createObjectNode();
        filter.set("includes", OBJECT_MAPPER.createArrayNode());
        filter.set("excludes", OBJECT_MAPPER.createArrayNode());
        return filter;
    }

    protected ObjectNode passwordAuth(String password) {
        if (isBlank(password)) {
            throw invalidConnection();
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

    private static MetadataIntegrationException invalidConnection() {
        return new MetadataIntegrationException(
                MetadataErrorCode.SOURCE_CONNECTION_ERROR,
                "Data source connection cannot be converted to the OpenMetadata 1.12.10 schema");
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
