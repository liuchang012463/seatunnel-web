package org.apache.seatunnel.web.api.log;

import java.time.Instant;
import java.util.List;

/**
 * Explainable fault-location result for a task instance.
 */
public record JobLogFaultDiagnosisResult(
        Long instanceId,
        String jobMode,
        boolean aiUsed,
        String provider,
        String faultType,
        String faultTypeLabel,
        double confidence,
        String rootCause,
        String affectedStage,
        List<String> evidence,
        List<String> recommendedActions,
        List<String> uncertainties,
        Instant generatedAt
) {
}
