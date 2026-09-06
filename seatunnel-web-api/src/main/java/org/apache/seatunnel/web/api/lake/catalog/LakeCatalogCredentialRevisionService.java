package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Locale;
import java.util.function.Function;

/**
 * Resolves source credentials only for an external catalog execution and
 * derives a non-reversible revision for the desired state.
 *
 * <p>The source {@link DataSource} remains the only credential store.  The
 * parsed {@link BaseConnectionParam} and the password-bearing execution
 * object are intentionally not retained by this service.  Callers should use
 * {@link #resolveForExecution(DataSource, LakeJdbcAdapterType)} inside their
 * external-operation callback and discard the returned value afterwards.</p>
 */
@Component
public class LakeCatalogCredentialRevisionService {

    private final LakeProperties properties;
    private final Function<DataSource, BaseConnectionParam> connectionParamFactory;

    /** Spring constructor; DataSourceUtils is invoked lazily by resolveForExecution. */
    @Autowired
    public LakeCatalogCredentialRevisionService(LakeProperties properties) {
        this(properties, source -> DataSourceUtils.buildJdbcConnectionParams(
                parserDbType(source), source.getConnectionParams()), null);
    }

    /** Injectable constructor for focused tests and alternate datasource plugins. */
    public LakeCatalogCredentialRevisionService(
            LakeProperties properties,
            Function<DataSource, BaseConnectionParam> connectionParamFactory) {
        this(properties, connectionParamFactory, null);
    }

    /**
     * Compatibility constructor.  The third argument is deliberately
     * ignored; lake catalog execution no longer has a feature-specific
     * secret or HMAC credential revision.
     */
    public LakeCatalogCredentialRevisionService(
            LakeProperties properties,
            Function<DataSource, BaseConnectionParam> connectionParamFactory,
            String ignoredSecret) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.connectionParamFactory = Objects.requireNonNull(
                connectionParamFactory, "connectionParamFactory");
    }

    /**
     * Resolves credentials and the non-secret endpoint for one external
     * operation.  No value returned by this method is suitable for a VO or a
     * persisted desired spec; it is an execution-only object.
     */
    public ExecutionCredentials resolveForExecution(
        DataSource source, LakeJdbcAdapterType adapter) {
        validateSource(source, adapter);

        final BaseConnectionParam param;
        try {
            // This is deliberately the only call site that parses the source
            // connection JSON.  It executes after the caller has acquired its
            // external-operation lease.
            param = connectionParamFactory.apply(source);
        } catch (RuntimeException ignored) {
            throw invalidSource();
        }
        if (param == null
                || StringUtils.isBlank(param.getUrl())
                || StringUtils.isBlank(param.getUser())
                || param.getPassword() == null
                || containsCredentialInUrl(param.getUrl())) {
            throw invalidSource();
        }

        String username = param.getUser().trim();
        String password = PasswordUtils.decodePassword(param.getPassword());
        String jdbcUrl = param.getUrl().trim();
        String revision = sourceConfigRevision(source, adapter, jdbcUrl, username);
        return new ExecutionCredentials(jdbcUrl, username, password, revision);
    }

    /** Computes the server-side revision without exposing the credential. */
    public String credentialRevision(DataSource source, LakeJdbcAdapterType adapter) {
        return resolveForExecution(source, adapter).credentialRevision();
    }

    /** Alias kept explicit for callers that prefer a verb-style method name. */
    public String computeCredentialRevision(DataSource source, LakeJdbcAdapterType adapter) {
        return credentialRevision(source, adapter);
    }

    /**
     * Kept for source compatibility.  Catalog execution is available whenever
     * the current source row can be resolved; there is no separate secret to
     * configure.
     */
    public boolean isConfigured() {
        return true;
    }

    /** Resolves an existing source by id without retaining it in this service. */
    public String credentialRevision(
            Long sourceDataSourceId,
            LakeJdbcAdapterType adapter,
            DataSourceDao dataSourceDao) {
        if (sourceDataSourceId == null || sourceDataSourceId <= 0
                || dataSourceDao == null) {
            throw invalidSource();
        }
        final DataSource source;
        try {
            source = dataSourceDao.queryById(sourceDataSourceId);
        } catch (RuntimeException ignored) {
            throw invalidSource();
        }
        return credentialRevision(source, adapter);
    }

    private void validateSource(DataSource source, LakeJdbcAdapterType adapter) {
        if (source == null || source.getId() == null || source.getId() <= 0
                || adapter == null || StringUtils.isBlank(source.getConnectionParams())
                || !matchesAdapter(source.getDbType(), adapter)) {
            throw invalidSource();
        }
        if (source.getStatus() != null
                && !"ENABLED".equalsIgnoreCase(source.getStatus().getCode())) {
            throw invalidSource();
        }
    }

    private static boolean matchesAdapter(DbType sourceType, LakeJdbcAdapterType adapter) {
        if (sourceType == null) {
            return false;
        }
        if (sourceType == DbType.JDBC) {
            return true;
        }
        return switch (adapter) {
            case MYSQL -> sourceType == DbType.MYSQL;
            case POSTGRESQL -> sourceType == DbType.POSTGRE_SQL;
            case ORACLE -> sourceType == DbType.ORACLE;
        };
    }

    private static DbType parserDbType(DataSource source) {
        if (source == null || source.getDbType() == null) {
            throw invalidSource();
        }
        return source.getDbType();
    }

    private static String sourceConfigRevision(DataSource source,
                                               LakeJdbcAdapterType adapter,
                                               String jdbcUrl,
                                               String username) {
        try {
            // This is a change/version marker, not an authorization token.
            // It intentionally excludes the password and changes whenever the
            // source endpoint or durable datasource row changes.
            String payload = "seatunnel-lake-source-config-v2" + '\u0000'
                    + source.getId() + '\u0000'
                    + adapter.code() + '\u0000'
                    + String.valueOf(source.getUpdateTime()) + '\u0000'
                    + jdbcUrl + '\u0000' + username;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return "source-config-" + result;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Source configuration revision unavailable");
        }
    }

    private static IllegalArgumentException invalidSource() {
        return new IllegalArgumentException("JDBC catalog source credentials are unavailable");
    }

    private static boolean containsCredentialInUrl(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(?:password|passwd|pwd|secret|token)\\s*[=:].*")
                || normalized.matches("jdbc:[^:]+://[^/@:]+:[^/@]+@.*");
    }


    /** Password-bearing object that must remain within one external call. */
    public static final class ExecutionCredentials {

        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final String credentialRevision;

        private ExecutionCredentials(
                String jdbcUrl, String username, String password, String credentialRevision) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.credentialRevision = credentialRevision;
        }

        @JsonIgnore
        public String jdbcUrl() {
            return jdbcUrl;
        }

        @JsonIgnore
        public String username() {
            return username;
        }

        @JsonIgnore
        public String password() {
            return password;
        }

        public String credentialRevision() {
            return credentialRevision;
        }

        /** Safe bean-style view for diagnostics; credentials have no bean getters. */
        public String getCredentialRevision() {
            return credentialRevision;
        }

        public LakeJdbcCatalogDdlBuilder.CatalogCredentials ddlCredentials() {
            return new LakeJdbcCatalogDdlBuilder.CatalogCredentials(username, password);
        }

        @Override
        public String toString() {
            return "ExecutionCredentials{jdbcUrl='[REDACTED]', username='[REDACTED]', "
                    + "password='[REDACTED]', credentialRevision='" + credentialRevision + "'}";
        }
    }
}
