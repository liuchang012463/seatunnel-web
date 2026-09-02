package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.CatalogPropertyRedactor;
import org.apache.seatunnel.web.api.lake.CatalogPropertyWhitelist;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.DorisSqlLiteral;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Generates the bounded Doris 4.1.2 JDBC catalog statements.
 *
 * <p>The desired spec contains no credentials.  User/password are accepted
 * only through the short-lived execution input below, while driver facts are
 * always read from the server-owned registry.</p>
 */
public final class LakeJdbcCatalogDdlBuilder {

    public String buildCreateCatalog(
            LakeCatalogDesiredSpec desiredSpec,
            LakeJdbcDriverRegistry driverRegistry,
            CatalogCredentials credentials) {
        return statement("CREATE CATALOG ", desiredSpec, driverRegistry, credentials);
    }

    public String buildAlterCatalog(
            LakeCatalogDesiredSpec desiredSpec,
            LakeJdbcDriverRegistry driverRegistry,
            CatalogCredentials credentials) {
        LakeCatalogDesiredSpec spec = LakeCatalogDesiredSpecValidator
                .validateAndNormalize(desiredSpec, driverRegistry);
        return "ALTER CATALOG " + quoteCatalog(spec.catalogName())
                + " SET PROPERTIES "
                + propertiesSql(properties(spec, driverRegistry, credentials));
    }

    /** Safe non-secret property update for callers that already resolved credentials elsewhere. */
    public String buildAlterCatalog(String catalogName, Map<String, String> properties) {
        String catalog = DorisIdentifier.validate(catalogName);
        Map<String, String> validated = CatalogPropertyWhitelist.validateAndCopy(properties);
        for (Map.Entry<String, String> entry : validated.entrySet()) {
            if ("user".equals(entry.getKey())
                    || CatalogPropertyRedactor.isSensitiveKey(entry.getKey())
                    || entry.getValue() == null) {
                throw new IllegalArgumentException("Catalog update contains a sensitive property");
            }
        }
        if (validated.isEmpty()) {
            throw new IllegalArgumentException("Catalog update properties must not be empty");
        }
        return "ALTER CATALOG " + quoteCatalog(catalog)
                + " SET PROPERTIES " + propertiesSql(new TreeMap<>(validated));
    }

    public String buildRefreshCatalog(String catalogName) {
        return "REFRESH CATALOG " + quoteCatalog(catalogName);
    }

    public String buildDropCatalog(String catalogName) {
        return "DROP CATALOG IF EXISTS " + quoteCatalog(catalogName);
    }

    /** Alias names make the bounded operations explicit at call sites. */
    public String refreshCatalog(String catalogName) {
        return buildRefreshCatalog(catalogName);
    }

    public String dropCatalog(String catalogName) {
        return buildDropCatalog(catalogName);
    }

    private String statement(
            String prefix,
            LakeCatalogDesiredSpec desiredSpec,
            LakeJdbcDriverRegistry driverRegistry,
            CatalogCredentials credentials) {
        LakeCatalogDesiredSpec spec = LakeCatalogDesiredSpecValidator
                .validateAndNormalize(desiredSpec, driverRegistry);
        return prefix + quoteCatalog(spec.catalogName()) + " PROPERTIES "
                + propertiesSql(properties(spec, driverRegistry, credentials));
    }

    private Map<String, String> properties(
            LakeCatalogDesiredSpec spec,
            LakeJdbcDriverRegistry driverRegistry,
            CatalogCredentials credentials) {
        Objects.requireNonNull(driverRegistry, "driver registry");
        Objects.requireNonNull(credentials, "catalog credentials");
        LakeJdbcDriverRegistry.DriverStatus status = driverRegistry.status(spec.adapter());
        if (!status.available()) {
            throw new IllegalArgumentException("JDBC catalog driver is not verified on Doris nodes");
        }
        LakeJdbcDriverRegistry.DriverRegistration driver = status.registration();
        if (driver == null) {
            throw new IllegalArgumentException("JDBC catalog driver is not registered");
        }

        TreeMap<String, String> values = new TreeMap<>();
        values.put("type", "jdbc");
        values.put("user", credentials.username());
        values.put("password", credentials.password());
        values.put("jdbc_url", spec.jdbcUrl());
        // A production registration is backed by a local/shared file and
        // deliberately has no remote URL.  Keep the Doris property name for
        // compatibility, but never emit an external endpoint when the
        // registry stores a local path.
        String driverLocation = StringUtils.defaultIfBlank(
                driver.url(), driver.driverLocation());
        if (StringUtils.isBlank(driverLocation)) {
            throw new IllegalArgumentException("JDBC catalog driver location is not registered");
        }
        values.put("driver_url", driverLocation);
        values.put("driver_class", driver.driverClass());
        // Web's registry checksum is SHA-256 and remains the artifact
        // identity.  Doris 4.1.2 accepts a separate optional 32-digit MD5
        // catalog checksum; never conflate or substitute one for the other.
        if (StringUtils.isNotBlank(driver.dorisMd5())) {
            values.put("checksum", driver.dorisMd5());
        }
        if (spec.scope() == LakeCatalogScope.ALL) {
            values.put("only_specified_database", "false");
        } else {
            values.put("only_specified_database", "true");
            values.put("include_database_list", String.join(",", spec.databaseInclude()));
            if (spec.scope() == LakeCatalogScope.TABLE) {
                values.put("include_table_list", spec.tableInclude().stream()
                        .map(table -> spec.databaseInclude().get(0) + "." + table)
                        .collect(Collectors.joining(",")));
            }
        }
        values.putAll(spec.options());
        return values;
    }

    private String propertiesSql(Map<String, String> properties) {
        return properties.entrySet().stream()
                .map(entry -> DorisSqlLiteral.quote(entry.getKey()) + " = "
                        + DorisSqlLiteral.quote(requiredValue(entry.getValue())))
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private String quoteCatalog(String catalogName) {
        return DorisIdentifier.quote(DorisIdentifier.validate(catalogName));
    }

    private String requiredValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Catalog property value must not be null");
        }
        return value;
    }

    /** Execution-only source credentials; its string form is always redacted. */
    public record CatalogCredentials(String username, String password) {

        public CatalogCredentials {
            if (StringUtils.isBlank(username) || password == null) {
                throw new IllegalArgumentException("Catalog credentials are incomplete");
            }
            username = username.trim();
        }

        /** Execution-only accessors must never be part of a JSON response. */
        @Override
        @JsonIgnore
        public String username() {
            return username;
        }

        @Override
        @JsonIgnore
        public String password() {
            return password;
        }

        @Override
        public String toString() {
            return "CatalogCredentials[username='[REDACTED]', password='[REDACTED]']";
        }
    }
}
