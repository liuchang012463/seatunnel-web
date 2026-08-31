package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.common.enums.MetadataRunStatus;

/** Maps only the PipelineStatus.pipelineState values in OpenMetadata 1.12.10. */
public final class OpenMetadataRunStatusMapper {

    private OpenMetadataRunStatusMapper() {
    }

    public static MetadataRunStatus fromPipelineState(String pipelineState) {
        if (pipelineState == null) {
            return MetadataRunStatus.UNKNOWN;
        }
        return switch (pipelineState) {
            case "queued" -> MetadataRunStatus.QUEUED;
            case "running" -> MetadataRunStatus.RUNNING;
            case "success", "partialSuccess" -> MetadataRunStatus.SUCCESS;
            case "failed", "stopped" -> MetadataRunStatus.FAILED;
            default -> MetadataRunStatus.UNKNOWN;
        };
    }
}
