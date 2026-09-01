package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
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

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HMAC_DOMAIN = "seatunnel-lake-catalog-credential-revision-v1";

    private final LakeProperties properties;
    private final Function<DataSource, BaseConnectionParam> connectionParamFactory;
    private final String configuredSecret;

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
     * Injectable constructor with an explicit secret.  The explicit value is
     * useful for embedders; production normally supplies the value through
     * {@code seatunnel.lake.catalog-credential-secret}.
     */
    public LakeCatalogCredentialRevisionService(
            LakeProperties properties,
            Function<DataSource, BaseConnectionParam> connectionParamFactory,
            String configuredSecret) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.connectionParamFactory = Objects.requireNonNull(
                connectionParamFactory, "connectionParamFactory");
        this.configuredSecret = firstNonBlank(
                configuredSecret,
                properties.getCatalogCredentialSecret(),
                // Keep existing installations usable while the dedicated
                // setting is rolled out.  The HMAC domain still separates
                // catalog revisions from preview-token signatures.
                properties.getPreviewTokenSecret());
    }

    /**
     * Resolves credentials and the non-secret endpoint for one external
     * operation.  No value returned by this method is suitable for a VO or a
     * persisted desired spec; it is an execution-only object.
     */
    public ExecutionCredentials resolveForExecution(
            DataSource source, LakeJdbcAdapterType adapter) {
        validateSource(source, adapter);
        requireSecret();

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
        String password = param.getPassword();
        String jdbcUrl = param.getUrl().trim();
        String revision = hmacRevision(source.getId(), adapter, username, password);
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

    private String hmacRevision(Long sourceId, LakeJdbcAdapterType adapter,
                                String username, String password) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    configuredSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String payload = HMAC_DOMAIN + '\u0000'
                    + sourceId + '\u0000'
                    + adapter.code() + '\u0000'
                    + username + '\u0000'
                    + password;
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            // HmacSHA256 is required by the JDK.  Do not attach an exception
            // that might contain plugin configuration or credential text.
            throw new IllegalStateException("Catalog credential revision unavailable");
        }
    }

    private void requireSecret() {
        if (StringUtils.isBlank(configuredSecret)) {
            throw new IllegalStateException("Catalog credential revision secret is not configured");
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        }
        return null;
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
