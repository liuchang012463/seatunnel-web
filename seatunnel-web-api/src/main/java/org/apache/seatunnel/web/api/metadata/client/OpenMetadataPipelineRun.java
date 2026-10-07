package org.apache.seatunnel.web.api.metadata.client;

/**
 * Minimal, read-only projection of OpenMetadata 2.0.4 PipelineStatus.
 * The source is /ingestionPipelines/{fqn}/pipelineStatus, not Airflow.
 */
public record OpenMetadataPipelineRun(
        String runId,
        String pipelineState,
        Long startDate,
        Long timestamp,
        Long endDate,
        Integer warningsCount) {
}
