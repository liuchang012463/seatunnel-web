package org.apache.seatunnel.web.api.log;

import java.util.List;

/**
 * Structured v1 view of a complete task log.
 *
 * <p>The four lists intentionally keep the original line metadata so the UI
 * can jump from a structured record back to the source log line.</p>
 */
public record JobLogAnalysisResult(
        Long instanceId,
        String jobMode,
        int totalLines,
        int errorCount,
        int warningCount,
        List<JobLogEntry> operationRecords,
        List<JobLogEntry> dataSnapshots,
        List<JobLogEntry> executionFlow,
        List<JobLogEntry> errors,
        List<JobLogEntry> timeline
) {
}
