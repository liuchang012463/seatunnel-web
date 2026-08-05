package org.apache.seatunnel.web.api.log;

/**
 * One step in the v1 operation replay timeline.
 */
public record JobLogReplayStep(
        long sequence,
        long lineNumber,
        String timestamp,
        Long elapsedMs,
        String source,
        String category,
        String eventType,
        String title,
        String status,
        String detail,
        String raw
) {
}
