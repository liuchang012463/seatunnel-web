package org.apache.seatunnel.web.api.log;

/**
 * A rule-derived record for the task-log observability views.
 *
 * <p>This is intentionally different from {@link JobLogEntry}: the UI uses
 * normalized operation fields instead of rendering a raw physical log line.</p>
 */
public record JobLogStructuredRecord(
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
        String detail
) {
}
