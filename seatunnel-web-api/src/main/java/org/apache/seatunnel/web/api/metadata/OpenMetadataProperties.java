package org.apache.seatunnel.web.api.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bootstrap-only OpenMetadata connection properties.
 *
 * <p>Runtime calls resolve through {@link OpenMetadataConfigResolver} from
 * {@code t_seatunnel_web_openmetadata_config}. These env-backed fields are used
 * only to seed that singleton row when the table is empty, and as a fixed
 * fallback for unit tests.</p>
 */
@Data
@ConfigurationProperties(prefix = "metadata.openmetadata")
public class OpenMetadataProperties {

    /** Explicit opt-in used when seeding an empty config table. */
    private boolean enabled = false;

    /** Must include the OpenMetadata /api base path, never an Airflow endpoint. */
    private String baseUrl;

    /** Bootstrap Bot JWT for first-time seed only; never log this value. */
    private String token;

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 10000;

    private String expectedServerVersion = "1.12.10";

    /** Proven Sprint 0 patch range, recorded so deployment cannot silently drift. */
    private String expectedIngestionPatch = "1.12.10.0";

    /** Optional host used only by the Kingbase metadata connector over an SSH tunnel. */
    private String kingbaseTunnelHost;

    /** Optional port used only by the Kingbase metadata connector over an SSH tunnel. */
    private int kingbaseTunnelPort;
}
