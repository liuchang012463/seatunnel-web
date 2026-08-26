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

    /** Lets OpenMetadata register a just-triggered run before local QUEUED is reconsidered. */
    private long triggerGraceSeconds = 60L;
}
