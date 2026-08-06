package org.apache.seatunnel.web.api.log;

import java.util.List;

/**
 * One bounded replay step containing the complete raw log for that phase.
 */
public record JobLogReplayStep(
        long sequence,
        long lineNumber,
        String timestamp,
        Long elapsedMs,
        String source,
        String category,
        String eventType,
        String operation,
        String target,
        String status,
        String detail,
        String title,
        List<String> logs
) {
}
