package org.apache.seatunnel.web.api.metadata.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/** OpenMetadata Server boundary. This interface deliberately exposes no Airflow API. */
public interface OpenMetadataClient {

    void assertFixedVersion();

    /** Reads Server and managed-ingestion health through OpenMetadata 1.12.10. */
    OpenMetadataHealth health();

    Optional<OpenMetadataEntity> findDatabaseService(String fullyQualifiedName);

    Optional<OpenMetadataDatabase> findDatabase(String fullyQualifiedName);

    List<OpenMetadataDatabase> listDatabases(String serviceFullyQualifiedName, int limit);

    /** Reads one cursor page from the OpenMetadata 1.12.10 database collection. */
    default OpenMetadataPage<OpenMetadataDatabase> listDatabasesPage(
            String serviceFullyQualifiedName, int limit, String after) {
        List<OpenMetadataDatabase> data = listDatabases(serviceFullyQualifiedName, limit);
        return new OpenMetadataPage<>(data, data.size(), null);
    }

    /** Lists non-deleted schemas belonging to one OpenMetadata Database FQN. */
    List<OpenMetadataDatabaseSchema> listSchemas(String databaseFullyQualifiedName, int limit);

    /** Reads one cursor page from the OpenMetadata 1.12.10 schema collection. */
    default OpenMetadataPage<OpenMetadataDatabaseSchema> listSchemasPage(
            String databaseFullyQualifiedName, int limit, String after) {
        List<OpenMetadataDatabaseSchema> data = listSchemas(databaseFullyQualifiedName, limit);
        return new OpenMetadataPage<>(data, data.size(), null);
    }

    /**
     * Lists non-deleted tables for one schema. The 1.12.10 contract uses the
     * fields query parameter to request columns and table constraints.
     */
    List<OpenMetadataTable> listTables(
            String schemaFullyQualifiedName, boolean includeColumns, int limit);

    /** Reads one cursor page from the OpenMetadata 1.12.10 table collection. */
    default OpenMetadataPage<OpenMetadataTable> listTablesPage(
            String schemaFullyQualifiedName, boolean includeColumns, int limit, String after) {
        List<OpenMetadataTable> data = listTables(schemaFullyQualifiedName, includeColumns, limit);
        return new OpenMetadataPage<>(data, data.size(), null);
    }

    /** Gets a table entity by its OpenMetadata UUID. */
    OpenMetadataTable getTable(String tableId);

    /**
     * Reads the latest table profile. The OpenMetadata endpoint is FQN based,
     * even though the local facade addresses tables by table id.
     */
    OpenMetadataTableProfile getLatestTableProfile(String tableFullyQualifiedName);

    /** Reads column profiles in the timestamp range required by OpenMetadata 1.12.10. */
    List<OpenMetadataColumnProfile> listColumnProfiles(
            String columnOrTableFullyQualifiedName, long startTs, long endTs);

    /** Convenience projection of the column profiles embedded in latest table profile. */
    default List<OpenMetadataColumnProfile> getLatestColumnProfiles(String tableFullyQualifiedName) {
        OpenMetadataTableProfile profile = getLatestTableProfile(tableFullyQualifiedName);
        return profile == null || profile.getColumns() == null
                ? List.of()
                : profile.getColumns();
    }

    /** Compatibility alias matching the entity resource terminology. */
    default List<OpenMetadataDatabaseSchema> listDatabaseSchemas(
            String databaseFullyQualifiedName, int limit) {
        return listSchemas(databaseFullyQualifiedName, limit);
    }

    /** Compatibility alias for callers that call the table-by-id operation a detail read. */
    default OpenMetadataTable getTableDetail(String tableId) {
        return getTable(tableId);
    }

    /** Reads the OpenMetadata tableProfilerConfig extension by table UUID. */
    JsonNode getTableProfilerConfig(String tableId);

    /** Updates the OpenMetadata tableProfilerConfig extension by table UUID. */
    JsonNode updateTableProfilerConfig(String tableId, JsonNode profilerConfig);

    /** Compatibility aliases for callers that use the shorter product term. */
    default JsonNode getProfilerConfig(String tableId) {
        return getTableProfilerConfig(tableId);
    }

    /** Compatibility aliases for callers that use the shorter product term. */
    default JsonNode updateProfilerConfig(String tableId, JsonNode profilerConfig) {
        return updateTableProfilerConfig(tableId, profilerConfig);
    }

    OpenMetadataEntity upsertDatabaseService(JsonNode request);

    Optional<OpenMetadataEntity> findIngestionPipeline(String fullyQualifiedName);

    OpenMetadataEntity upsertIngestionPipeline(JsonNode request);

    void deployIngestionPipeline(String id);

    /**
     * Ensures the managed Airflow DAG is enabled through OpenMetadata 1.12.10.
     * This is deliberately not an Airflow client operation.
     */
    void enableIngestionPipeline(String id);

    /** OpenMetadata 1.12.10 trigger endpoint; it intentionally has no request body. */
    void triggerIngestionPipeline(String id);

    /** Reads PipelineStatus from OpenMetadata, never from an Airflow endpoint. */
    List<OpenMetadataPipelineRun> listIngestionPipelineRuns(String fullyQualifiedName, int limit);

    /** OpenMetadata 1.12.10 kill endpoint; it intentionally has no request body. */
    void killIngestionPipeline(String id);

    /** OM 404 is already the desired state and returns normally. */
    void deleteIngestionPipeline(String id);

    /** OM 404 is already the desired state and returns normally. */
    void deleteDatabaseServiceRecursively(String id);
}
