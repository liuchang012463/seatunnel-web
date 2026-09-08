package org.apache.seatunnel.web.api.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Legacy env-shaped OpenMetadata fields retained only for unit-test helpers
 * ({@code OpenMetadataConfigResolver.fixed(properties)}). Production runtime
 * never reads these values — configure OM under 运行运维 → 探查引擎管理.
 */
@Data
@ConfigurationProperties(prefix = "metadata.openmetadata")
public class OpenMetadataProperties {

    private boolean enabled = false;

    private String baseUrl;

    /** Never log this value. */
    private String token;

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 10000;

    private String expectedServerVersion = "1.12.10";

    private String expectedIngestionPatch = "1.12.10.0";

    private String kingbaseTunnelHost;

    private int kingbaseTunnelPort;
}
