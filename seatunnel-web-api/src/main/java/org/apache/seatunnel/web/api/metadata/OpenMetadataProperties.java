package org.apache.seatunnel.web.api.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Legacy env-shaped OpenMetadata fields retained only for unit-test helpers.
 * Production runtime never reads these values.
 */
@Data
@ConfigurationProperties(prefix = "metadata.openmetadata")
public class OpenMetadataProperties {

    private boolean enabled = false;

    private String baseUrl;

    /** Never log this value. */
    private String token;

    private int connectTimeoutMs = 10_000;

    private int readTimeoutMs = 60_000;

    private String expectedServerVersion = "1.12.10";

    private String expectedIngestionPatch = "1.12.10.0";
}
