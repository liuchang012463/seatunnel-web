package org.apache.seatunnel.web.api.log;

/**
 * One server-sent event emitted while a failed task is being diagnosed.
 */
public record JobLogDiagnosisStreamEvent(
        String type,
        String content,
        JobLogFaultDiagnosisResult result
) {

    public static JobLogDiagnosisStreamEvent status(String content) {
        return new JobLogDiagnosisStreamEvent("status", content, null);
    }

    public static JobLogDiagnosisStreamEvent delta(String content) {
        return new JobLogDiagnosisStreamEvent("delta", content, null);
    }

    public static JobLogDiagnosisStreamEvent result(JobLogFaultDiagnosisResult result) {
        return new JobLogDiagnosisStreamEvent("result", null, result);
    }

    public static JobLogDiagnosisStreamEvent done() {
        return new JobLogDiagnosisStreamEvent("done", null, null);
    }
}
