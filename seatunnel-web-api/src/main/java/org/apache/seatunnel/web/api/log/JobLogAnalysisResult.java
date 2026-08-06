package org.apache.seatunnel.web.api.log;

import java.util.List;

/**
 * Rule-derived view of a complete task log.
 *
 * <p>The observability lists contain normalized records. Raw entries remain
 * available in {@link #errors()} for evidence and fault diagnosis.</p>
 */
public record JobLogAnalysisResult(
        Long instanceId,
        String jobMode,
        int totalLines,
        int errorCount,
        int warningCount,
        List<JobLogStructuredRecord> operationRecords,
        List<JobLogStructuredRecord> dataSnapshots,
        List<JobLogStructuredRecord> executionFlow,
        List<JobLogEntry> errors,
        List<JobLogStructuredRecord> timeline
) {
}
