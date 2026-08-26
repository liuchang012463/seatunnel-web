package org.apache.seatunnel.web.api.metadata.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/** OpenMetadata Server boundary. This interface deliberately exposes no Airflow API. */
public interface OpenMetadataClient {

    void assertFixedVersion();

    Optional<OpenMetadataEntity> findDatabaseService(String fullyQualifiedName);

    OpenMetadataEntity upsertDatabaseService(JsonNode request);

    Optional<OpenMetadataEntity> findIngestionPipeline(String fullyQualifiedName);

    OpenMetadataEntity upsertIngestionPipeline(JsonNode request);

    void deployIngestionPipeline(String id);

    /** OM 404 is already the desired state and returns normally. */
    void deleteIngestionPipeline(String id);

    /** OM 404 is already the desired state and returns normally. */
    void deleteDatabaseServiceRecursively(String id);
}
