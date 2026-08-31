package org.apache.seatunnel.web.api.lake;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Server-side settings for the v1.4 lake control plane.
 *
 * <p>The lake data source is an existing SeaTunnel data source.  Credentials are
 * deliberately not represented by this object; they are read only from the
 * data-source record when a connection pool is created.</p>
 */
@Data
@ConfigurationProperties(prefix = "seatunnel.lake")
public class LakeProperties {

    private boolean enabled;

    /** The existing Doris data-source id used by the lake control plane. */
    private Long dataSourceId;

    /** How long an unfinished operation may remain leased before Retry can take it over. */
    private Duration operationStaleAfter = Duration.ofMinutes(15);

    /** Maximum time allowed for a read-only metadata query. */
    private Duration queryTimeout = Duration.ofSeconds(30);

    /**
     * HMAC key used for short-lived, one-time MANAGED table preview tokens.
     * When unset, a process-local random key is generated at startup; a shared
     * value is required when several Web instances serve the same requests.
     */
    private String previewTokenSecret;

    /** Preview tokens are intentionally short lived. */
    private Duration previewTokenTtl = Duration.ofMinutes(5);

    private long maxRows = 10_000;

    private long maxBytes = 10 * 1024 * 1024L;

    /** Server-managed JDBC driver metadata. Driver upload is intentionally unsupported. */
    private String driverUrl;

    private String driverClass = "com.mysql.cj.jdbc.Driver";

    private String driverChecksum;

    private ConnectionPool connectionPool = new ConnectionPool();

    @Data
    public static class ConnectionPool {

        private int maximumPoolSize = 4;

        private int minimumIdle = 0;

        private Duration connectionTimeout = Duration.ofSeconds(10);

        private Duration validationTimeout = Duration.ofSeconds(5);
    }
}
