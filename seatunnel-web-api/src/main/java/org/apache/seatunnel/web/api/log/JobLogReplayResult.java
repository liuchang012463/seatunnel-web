package org.apache.seatunnel.web.api.log;

import java.util.List;

/**
 * Named, ordered, replayable representation of a complete task log.
 */
public record JobLogReplayResult(
        Long instanceId,
        String jobMode,
        int totalSections,
        int totalSteps,
        Long durationMs,
        List<JobLogReplaySection> sections
) {
}
