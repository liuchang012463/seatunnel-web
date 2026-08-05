package org.apache.seatunnel.web.api.log;

/**
 * One physical log line enriched with the stable fields used by search and
 * the later analysis/replay views.
 */
public record JobLogEntry(
        long sequence,
        long lineNumber,
        String timestamp,
        String level,
        String source,
        String category,
        String eventType,
        String message,
        String raw,
        Long elapsedMs
) {
}
