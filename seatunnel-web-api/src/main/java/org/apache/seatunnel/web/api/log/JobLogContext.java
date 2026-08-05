package org.apache.seatunnel.web.api.log;

import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;

/**
 * Runtime identifiers needed to combine the Web-side and Engine-side logs of
 * one task instance.
 */
public record JobLogContext(
        Long instanceId,
        Long jobDefinitionId,
        Long clientId,
        String engineJobId,
        JobMode jobMode,
        String runtimeConfig,
        String logPath,
        String jobStatus
) {

    public boolean hasEngineLogReference() {
        return clientId != null && clientId > 0
                && engineJobId != null && !engineJobId.isBlank();
    }

    public boolean isTerminal() {
        if (jobStatus == null || jobStatus.isBlank()) {
            return false;
        }
        try {
            return JobStatus.fromString(jobStatus).isEndState();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
