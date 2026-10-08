package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.api.metadata.OmResourceType;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.List;

/** Converts one supported SeaTunnel source to fixed OpenMetadata 2.0.4 DTO shapes. */
public interface MetadataConnectorAdapter {

    DbType dataSourceType();

    String openMetadataServiceType();

    default MetadataServiceCategory serviceCategory() {
        return MetadataServiceCategory.DATABASE;
    }

    default boolean supportsProfiler() {
        return serviceCategory() == MetadataServiceCategory.DATABASE;
    }

    /**
     * Non-relational OpenMetadata asset families exposed by this connector. Database
     * connectors stay empty: their assets are reached through Database/Schema/Table.
     */
    default List<OmResourceType> resourceTypes() {
        return List.of();
    }

    /**
     * Whether the connector can collect sample payloads (topic messages, container rows,
     * file rows) into OpenMetadata. Off unless the operator opts the data source in.
     */
    default boolean supportsSampleData() {
        return false;
    }

    /** Preferred entry; default delegates to databaseServiceRequest for DB adapters. */
    default JsonNode serviceRequest(DataSource dataSource, String stableServiceName) {
        return databaseServiceRequest(dataSource, stableServiceName);
    }

    /**
     * Service request with the operator's sample-data decision applied. Only connectors
     * whose sample collection is a connection flag (SFTP) override this overload.
     */
    default JsonNode serviceRequest(
            DataSource dataSource, String stableServiceName, boolean sampleDataEnabled) {
        return serviceRequest(dataSource, stableServiceName);
    }

    JsonNode databaseServiceRequest(DataSource dataSource, String stableServiceName);

    JsonNode metadataPipelineRequest(String pipelineName, String serviceId, String serviceFqn);

    /** Request shape for the existing DataSource; the MVP keeps it manual-only. */
    default JsonNode metadataPipelineRequest(
            DataSource dataSource, String pipelineName, String serviceId, String serviceFqn) {
        return metadataPipelineRequest(pipelineName, serviceId, serviceFqn);
    }

    /**
     * Metadata pipeline request with the operator's sample-data decision applied. Only
     * connectors whose sample collection is a pipeline flag (Kafka) override this.
     */
    default JsonNode metadataPipelineRequest(
            DataSource dataSource,
            String pipelineName,
            String serviceId,
            String serviceFqn,
            boolean sampleDataEnabled) {
        return metadataPipelineRequest(dataSource, pipelineName, serviceId, serviceFqn);
    }

    JsonNode profilerPipelineRequest(String pipelineName, String serviceId, String serviceFqn);

    /**
     * 2.0.4 profiler runs are scoped by an OM Database FQN. Existing reconciliation
     * continues to create the reusable pipeline with an empty filter.
     */
    default JsonNode profilerPipelineRequest(
            String pipelineName, String serviceId, String serviceFqn, String databaseFqn) {
        if (databaseFqn == null || databaseFqn.isBlank()) {
            return profilerPipelineRequest(pipelineName, serviceId, serviceFqn);
        }
        throw new UnsupportedOperationException("Database-scoped profiler is not implemented by this connector");
    }

    /**
     * Builds a profiler request scoped to one Database and, optionally, one Schema.
     * Existing connectors keep the database-only behavior unless they opt into the
     * schema-aware overload.
     */
    default JsonNode profilerPipelineRequest(
            String pipelineName,
            String serviceId,
            String serviceFqn,
            String databaseFqn,
            String schemaFqn) {
        if (schemaFqn == null || schemaFqn.isBlank()) {
            return profilerPipelineRequest(pipelineName, serviceId, serviceFqn, databaseFqn);
        }
        throw new UnsupportedOperationException("Schema-scoped profiler is not implemented by this connector");
    }
}
