package org.apache.seatunnel.web.api.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime boundary for the fixed OpenMetadata 1.12.10 server. */
@Data
@ConfigurationProperties(prefix = "metadata.openmetadata")
public class OpenMetadataProperties {

    /** Explicit opt-in prevents an existing installation from being called after upgrade. */
    private boolean enabled = false;

    /** Must include the OpenMetadata /api base path, never an Airflow endpoint. */
    private String baseUrl;

    /** Kept in an environment variable; never log this value. */
    private String token;

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 10000;

    private String expectedServerVersion = "1.12.10";

    /** Proven Sprint 0 patch range, recorded so deployment cannot silently drift. */
    private String expectedIngestionPatch = "1.12.10.0";
}
