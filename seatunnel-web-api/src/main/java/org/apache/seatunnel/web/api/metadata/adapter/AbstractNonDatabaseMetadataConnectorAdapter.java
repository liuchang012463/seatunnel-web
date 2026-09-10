package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.dao.entity.DataSource;

/** Shared helpers for non-database metadata connectors (Kafka, ES, S3, etc.). */
public abstract class AbstractNonDatabaseMetadataConnectorAdapter implements MetadataConnectorAdapter {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName) {
        throw new UnsupportedOperationException(
                "Non-database connectors must use serviceRequest instead of databaseServiceRequest");
    }

    @Override
    public JsonNode metadataPipelineRequest(String pipelineName, String serviceId, String serviceFqn) {
        return metadataPipelineRequestInternal(pipelineName, serviceId, serviceFqn, "0 0 1 1 *");
    }

    @Override
    public JsonNode metadataPipelineRequest(
            DataSource dataSource, String pipelineName, String serviceId, String serviceFqn) {
        return metadataPipelineRequestInternal(pipelineName, serviceId, serviceFqn, null);
    }

    @Override
    public JsonNode profilerPipelineRequest(String pipelineName, String serviceId, String serviceFqn) {
        throw profilerNotSupported();
    }

    @Override
    public JsonNode profilerPipelineRequest(
            String pipelineName, String serviceId, String serviceFqn, String databaseFqn) {
        throw profilerNotSupported();
    }

    @Override
    public JsonNode profilerPipelineRequest(
            String pipelineName,
            String serviceId,
            String serviceFqn,
            String databaseFqn,
            String schemaFqn) {
        throw profilerNotSupported();
    }

    protected ObjectNode baseServiceRequest(DataSource dataSource, String stableServiceName) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("name", stableServiceName);
        root.put("displayName", dataSource.getName());
        root.put("serviceType", openMetadataServiceType());
        return root;
    }

    protected JsonNode metadataPipelineRequestInternal(
            String pipelineName, String serviceId, String serviceFqn, String scheduleInterval) {
        ObjectNode config = OBJECT_MAPPER.createObjectNode();
        config.put("type", serviceCategory().metadataConfigType());
        return pipelineRequest(pipelineName, serviceId, serviceFqn, "metadata", config, scheduleInterval);
    }

    protected ObjectNode pipelineRequest(
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
        service.put("type", serviceCategory().entityType());
        service.put("name", serviceFqn);
        service.put("fullyQualifiedName", serviceFqn);
        root.put("pipelineType", pipelineType);
        root.putObject("sourceConfig").set("config", config);
        ObjectNode airflow = root.putObject("airflowConfig");
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

    protected String decodePassword(String password) {
        if (isBlank(password)) {
            return password;
        }
        return PasswordUtils.decodePassword(password);
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
        JsonNode value = source == null ? null : source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private UnsupportedOperationException profilerNotSupported() {
        return new UnsupportedOperationException(
                "Profiler pipelines are not supported for " + serviceCategory().name() + " services");
    }
}
