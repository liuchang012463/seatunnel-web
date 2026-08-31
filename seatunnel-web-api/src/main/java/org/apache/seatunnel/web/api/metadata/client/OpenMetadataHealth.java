package org.apache.seatunnel.web.api.metadata.client;

/**
 * Read-only health projection from the OpenMetadata 1.12.10 control plane.
 * The orchestrator value is obtained through OpenMetadata's
 * ingestion-pipeline status endpoint, never by calling Airflow directly.
 */
public record OpenMetadataHealth(
        boolean openMetadataUp,
        boolean orchestratorUp,
        String serverVersion,
        String ingestionVersion) {
}
