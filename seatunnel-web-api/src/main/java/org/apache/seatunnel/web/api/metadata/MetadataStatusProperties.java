package org.apache.seatunnel.web.api.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "metadata.status")
public class MetadataStatusProperties {

    /** Scheduler cadence while a cached run is QUEUED/RUNNING. */
    private long intervalMs = 10_000L;

    private int batchSize = 50;

    private long idleRefreshSeconds = 60L;

    /** Waits for pipeline runs to appear after both initial trigger calls complete. */
    private long triggerGraceSeconds = 120L;

    /** Bounds an abandoned exploration while its pipelines are prepared and initial triggers/retries run. */
    private long explorationPreparationTimeoutSeconds = 1_800L;

    /** Upper bound for retrying transient trigger HTTP 400 responses. */
    private long explorationTriggerRetryTimeoutSeconds = 1_200L;
}
