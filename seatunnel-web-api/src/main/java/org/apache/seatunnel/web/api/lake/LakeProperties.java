package org.apache.seatunnel.web.api.lake;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.Duration;

/**
 * Runtime limits for the always-on lake control plane.
 *
 * <p>Connection credentials and JDBC driver registrations live in the lake
 * warehouse tables.  This class intentionally contains only safe operational
 * defaults and never binds the removed lake-prefixed environment variables.</p>
 */
@Data
public class LakeProperties {

    /** How long an unfinished operation may remain leased before Retry can take it over. */
    private Duration operationStaleAfter = Duration.ofMinutes(15);

    /** Maximum time allowed for a read-only metadata query. */
    private Duration queryTimeout = Duration.ofSeconds(30);

    /** How long an explicit Doris-side source reachability probe is reusable. */
    private Duration sourceProbeCacheTtl = Duration.ofSeconds(60);

    private long maxRows = 10_000;

    private long maxBytes = 10 * 1024 * 1024L;

    private ConnectionPool connectionPool = new ConnectionPool();

    /**
     * Compatibility-only values for callers compiled against the pre-v1.4
     * API.  They are ignored by Spring configuration binding and are never
     * consulted by the lake runtime; the database-backed warehouse service is
     * the sole source of truth.
     */
    @JsonIgnore
    @Deprecated
    private Long dataSourceId;

    @JsonIgnore
    @Deprecated
    private Duration previewTokenTtl = Duration.ofMinutes(5);

    @JsonIgnore
    @Deprecated
    private JdbcCatalog jdbcCatalog = new JdbcCatalog();

    /** The capability is contractual and cannot be disabled by configuration. */
    @JsonIgnore
    @Deprecated
    public boolean isEnabled() {
        return true;
    }

    /**
     * Source compatibility for integrations that used the old opt-in flag.
     * The value is deliberately ignored: lake support is contractual and is
     * always available (or reports that the warehouse has not been configured).
     */
    @Deprecated
    public void setEnabled(boolean ignored) {
        // Intentionally ignored.
    }

    /*
     * Source-compatible setters for integrations that used the pre-v1.4
     * driver fields.  They intentionally do not become configuration state:
     * all live driver facts are read from t_seatunnel_web_lake_jdbc_driver.
     */
    @Deprecated
    public void setDriverUrl(String ignored) {
        // Intentionally ignored.
    }

    @Deprecated
    public void setDriverClass(String ignored) {
        // Intentionally ignored.
    }

    @Deprecated
    public void setDriverChecksum(String ignored) {
        // Intentionally ignored.
    }

    @Deprecated
    public void setCatalogCredentialSecret(String ignored) {
        // Catalog HMAC credentials were removed; the argument is ignored.
    }

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

    /**
     * Compatibility DTOs used by older embedders.  Spring no longer binds
     * these values; the live registry is database-backed.
     */
    @Deprecated
    @Data
    public static class JdbcCatalog {

        /** Bumped by operators when the server-side driver inventory changes. */
        private String registryRevision;

        private Driver mysql = Driver.mysqlDefaults();

        private Driver postgresql = Driver.postgresqlDefaults();

        private Driver oracle = Driver.oracleDefaults();

        // Explicit accessors keep the compatibility shape usable even when
        // this class is compiled in an annotation-processing-isolated test.
        public String getRegistryRevision() {
            return registryRevision;
        }

        public Driver getMysql() {
            return mysql;
        }

        public Driver getPostgresql() {
            return postgresql;
        }

        public Driver getOracle() {
            return oracle;
        }
    }

    /** Compatibility shape for old tests and integrations. */
    @Deprecated
    @Data
    public static class Driver {

        private boolean enabled;

        private String url;

        private String driverClass;

        private String checksum;

        /** Optional Doris 4.1.2 catalog checksum; unlike checksum, this is an MD5. */
        private String dorisMd5;

        /** True only after operators verify the driver on Doris FE/BE nodes. */
        private boolean verified;

        public boolean isEnabled() {
            return enabled;
        }

        public String getUrl() {
            return url;
        }

        public String getDriverClass() {
            return driverClass;
        }

        public String getChecksum() {
            return checksum;
        }

        public String getDorisMd5() {
            return dorisMd5;
        }

        public boolean isVerified() {
            return verified;
        }

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
