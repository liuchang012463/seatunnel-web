package org.apache.seatunnel.web.api.log;

import java.util.List;

/**
 * A named contiguous phase of the task operation replay.
 */
public record JobLogReplaySection(
        String id,
        String title,
        String category,
        String startTime,
        String endTime,
        Long durationMs,
        List<JobLogReplayStep> steps
) {
}
