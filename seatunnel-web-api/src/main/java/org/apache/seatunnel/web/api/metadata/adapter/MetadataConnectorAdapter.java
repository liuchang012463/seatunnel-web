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

    /**
     * Whether sample collection runs as its own auto-classification pipeline. Storage
     * services need this because their metadata pipeline has no sample-data flag;
     * connectors that carry the flag themselves (Kafka) leave it false.
     */
    default boolean collectsSampleDataViaAutoClassification() {
        return false;
    }

    /**
     * Whether this connector derives a container data model from an object-storage
     * manifest supplied by the operator.
     */
    default boolean supportsStorageManifest() {
        return false;
    }

    /** Preferred entry; default delegates to databaseServiceRequest for DB adapters. */
    default JsonNode serviceRequest(DataSource dataSource, String stableServiceName) {
        return databaseServiceRequest(dataSource, stableServiceName);
    }

    /**
     * Service request with the operator's decisions applied. Only connectors whose sample
     * collection is a connection flag (SFTP) override this overload.
     */
    default JsonNode serviceRequest(
            DataSource dataSource, String stableServiceName, MetadataSyncOptions options) {
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
     * Metadata pipeline request with the operator's decisions applied. Connectors whose
     * pipeline shape depends on them (Kafka sample data, storage manifest) override this.
     */
    default JsonNode metadataPipelineRequest(
            DataSource dataSource,
            String pipelineName,
            String serviceId,
            String serviceFqn,
            MetadataSyncOptions options) {
        return metadataPipelineRequest(dataSource, pipelineName, serviceId, serviceFqn);
    }

    /**
     * Auto-classification pipeline used only to collect sample data. The connector keeps
     * PII classification off; Web does not run the classification agent.
     */
    default JsonNode autoClassificationPipelineRequest(
            String pipelineName, String serviceId, String serviceFqn, MetadataSyncOptions options) {
        throw new UnsupportedOperationException(
                "Auto-classification pipelines are not supported by this connector");
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

    /** Builds the separate sample-only workflow used to persist native OM sample data. */
    default JsonNode autoClassificationPipelineRequest(
            String pipelineName, String serviceId, String serviceFqn) {
        throw new UnsupportedOperationException(
                "AutoClassification pipelines are not implemented by this connector");
    }

    /** Builds a sample-only workflow scoped to the same database and schema as exploration. */
    default JsonNode autoClassificationPipelineRequest(
            String pipelineName,
            String serviceId,
            String serviceFqn,
            String databaseFqn,
            String schemaFqn) {
        if ((databaseFqn == null || databaseFqn.isBlank())
                && (schemaFqn == null || schemaFqn.isBlank())) {
            return autoClassificationPipelineRequest(pipelineName, serviceId, serviceFqn);
        }
        throw new UnsupportedOperationException(
                "Scoped AutoClassification pipelines are not implemented by this connector");
    }
}
