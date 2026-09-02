package org.apache.seatunnel.web.api.lake;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
     * This value is required when the lake control plane is enabled.  A
     * process-local random key is used only while the opt-in control plane is
     * disabled, so a disabled default can still start without a secret.
     */
    private String previewTokenSecret;

    /**
     * Server-only HMAC key used to derive a stable revision for source
     * credentials.  It is deliberately separate from the desired catalog
     * spec and must never be serialized as part of a capability response.
     */
    @JsonIgnore
    private String catalogCredentialSecret;

    /** Preview tokens are intentionally short lived. */
    private Duration previewTokenTtl = Duration.ofMinutes(5);

    private long maxRows = 10_000;

    private long maxBytes = 10 * 1024 * 1024L;

    /** Server-managed JDBC driver metadata. Driver upload is intentionally unsupported. */
    private String driverUrl;

    private String driverClass = "com.mysql.cj.jdbc.Driver";

    private String driverChecksum;

    /**
     * Server-owned configuration for logical JDBC catalogs.  It is kept
     * separate from the Doris control-plane driver fields above: the latter
     * describe Web's own Doris connection, while these entries describe the
     * source drivers loaded by Doris FE/BE nodes.
     */
    private JdbcCatalog jdbcCatalog = new JdbcCatalog();

    private ConnectionPool connectionPool = new ConnectionPool();

    @Data
    public static class ConnectionPool {

        private int maximumPoolSize = 4;

        private int minimumIdle = 0;

        /** The structured query path gets its own smaller, read-only pool. */
        private int readOnlyMaximumPoolSize = 2;

        private int readOnlyMinimumIdle = 0;

        private Duration connectionTimeout = Duration.ofSeconds(10);

        private Duration validationTimeout = Duration.ofSeconds(5);
    }

    /** Logical JDBC catalog driver registry; no credentials are accepted here. */
    @Data
    public static class JdbcCatalog {

        /** Bumped by operators when the server-side driver inventory changes. */
        private String registryRevision;

        private Driver mysql = Driver.mysqlDefaults();

        private Driver postgresql = Driver.postgresqlDefaults();

        private Driver oracle = Driver.oracleDefaults();
    }

    /** One server-configured JDBC driver, intentionally credential-free. */
    @Data
    public static class Driver {

        private boolean enabled;

        private String url;

        private String driverClass;

        private String checksum;

        /** True only after operators verify the driver on Doris FE/BE nodes. */
        private boolean verified;

        public static Driver mysqlDefaults() {
            Driver driver = new Driver();
            driver.driverClass = "com.mysql.cj.jdbc.Driver";
            return driver;
        }

        public static Driver postgresqlDefaults() {
            Driver driver = new Driver();
            driver.driverClass = "org.postgresql.Driver";
            return driver;
        }

        public static Driver oracleDefaults() {
            Driver driver = new Driver();
            driver.driverClass = "oracle.jdbc.OracleDriver";
            return driver;
        }
    }
}
