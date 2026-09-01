package org.apache.seatunnel.web.api.lake.query;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Server-owned execution bounds for the dedicated read-only query path. */
@Data
@ConfigurationProperties(prefix = "seatunnel.lake.query")
public class LakeReadOnlyQueryProperties {

    private Duration queryTimeout = Duration.ofSeconds(30);

    private long maxRows = 10_000;

    private long maxBytes = 10 * 1024 * 1024L;
}
