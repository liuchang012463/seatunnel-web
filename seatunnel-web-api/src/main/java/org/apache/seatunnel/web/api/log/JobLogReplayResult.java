package org.apache.seatunnel.web.api.log;

import java.util.List;

/**
 * Ordered, replayable representation of a complete task log.
 */
public record JobLogReplayResult(
        Long instanceId,
        String jobMode,
        int totalSteps,
        Long durationMs,
        List<JobLogReplayStep> steps
) {
}
