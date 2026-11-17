package org.apache.seatunnel.web.api.metadata.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Fields needed to update an existing OpenMetadata ingestion pipeline in place. */
public record OpenMetadataPipelineConfig(
        String id, String serviceId, String type, String schedule, JsonNode sourceConfig, boolean deployed) {
}
