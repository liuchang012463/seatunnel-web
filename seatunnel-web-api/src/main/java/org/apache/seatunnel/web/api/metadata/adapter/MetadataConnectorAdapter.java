package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;

/** Converts one supported SeaTunnel source to fixed OpenMetadata 1.12.10 DTO shapes. */
public interface MetadataConnectorAdapter {

    DbType dataSourceType();

    String openMetadataServiceType();

    JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName);

    JsonNode metadataPipelineRequest(String pipelineName, String serviceId, String serviceFqn);

    /** System-generated daily schedule for the existing DataSource. */
    default JsonNode metadataPipelineRequest(
            DataSource dataSource, String pipelineName, String serviceId, String serviceFqn) {
        return metadataPipelineRequest(pipelineName, serviceId, serviceFqn);
    }

    JsonNode profilerPipelineRequest(String pipelineName, String serviceId, String serviceFqn);

    /**
     * 1.12.10 profiler runs are scoped by an OM Database FQN. Existing reconciliation
     * continues to create the reusable pipeline with an empty filter.
     */
    default JsonNode profilerPipelineRequest(
            String pipelineName, String serviceId, String serviceFqn, String databaseFqn) {
        if (databaseFqn == null || databaseFqn.isBlank()) {
            return profilerPipelineRequest(pipelineName, serviceId, serviceFqn);
        }
        throw new UnsupportedOperationException("Database-scoped profiler is not implemented by this connector");
    }
}
