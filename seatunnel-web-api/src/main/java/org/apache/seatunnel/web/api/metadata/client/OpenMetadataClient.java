package org.apache.seatunnel.web.api.metadata.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/** OpenMetadata Server boundary. This interface deliberately exposes no Airflow API. */
public interface OpenMetadataClient {

    void assertFixedVersion();

    Optional<OpenMetadataEntity> findDatabaseService(String fullyQualifiedName);

    Optional<OpenMetadataDatabase> findDatabase(String fullyQualifiedName);

    List<OpenMetadataDatabase> listDatabases(String serviceFullyQualifiedName, int limit);

    OpenMetadataEntity upsertDatabaseService(JsonNode request);

    Optional<OpenMetadataEntity> findIngestionPipeline(String fullyQualifiedName);

    OpenMetadataEntity upsertIngestionPipeline(JsonNode request);

    void deployIngestionPipeline(String id);

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
