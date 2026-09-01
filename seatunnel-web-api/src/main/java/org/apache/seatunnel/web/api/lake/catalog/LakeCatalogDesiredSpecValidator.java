package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.CatalogPropertyRedactor;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict validation and canonical normalization for a desired catalog spec. */
public final class LakeCatalogDesiredSpecValidator {

    private static final Pattern DRIVER_CLASS = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Set<String> OPTIONS = Set.of(
            "lower_case_table_names",
            "lower_case_database_names",
            "lower_case_meta_names",
            "use_meta_cache",
            "enable_meta_cache",
            "meta_cache_expiration_second",
            "enable_partition_cache",
            "connection_pool_min_size",
            "connection_pool_max_size",
            "connection_pool_max_wait_time",
            "connection_pool_max_life_time",
            "connection_pool_keep_alive");

    private LakeCatalogDesiredSpecValidator() {
    }

    public static LakeCatalogDesiredSpec validateAndNormalize(LakeCatalogDesiredSpec spec) {
        return validateAndNormalize(spec, null);
    }

    /** Also verifies that driver facts came from the server-side registry. */
    public static LakeCatalogDesiredSpec validateAndNormalize(
            LakeCatalogDesiredSpec spec,
            LakeJdbcDriverRegistry driverRegistry) {
        Objects.requireNonNull(spec, "desired catalog spec");
        String catalogName = normalizeCatalogName(spec.catalogName());
        if (spec.sourceDataSourceId() == null || spec.sourceDataSourceId() <= 0) {
            throw invalid("source data source id");
        }
        String sourceRevision = required(spec.sourceDataSourceRevision(), "source revision");
        LakeJdbcAdapterType adapter = Objects.requireNonNull(spec.adapter(), "adapter");
        LakeCatalogScope scope = Objects.requireNonNull(spec.scope(), "scope");
        String jdbcUrl = required(spec.jdbcUrl(), "JDBC endpoint");
        validateJdbcEndpoint(jdbcUrl);
        String driverUrl = required(spec.driverUrl(), "driver URL");
        validateDriverUrl(driverUrl);
        String driverClass = required(spec.driverClass(), "driver class");
        if (!DRIVER_CLASS.matcher(driverClass).matches()) {
            throw invalid("driver class");
        }
        String checksum = required(spec.driverChecksum(), "driver checksum");
        if (!SHA256.matcher(checksum).matches()) {
            throw invalid("driver checksum");
        }
        String registryRevision = required(spec.driverRegistryRevision(), "driver registry revision");
        String credentialRevision = required(spec.credentialRevision(), "credential revision");

        List<String> databases = normalizeIdentifiers(spec.databaseInclude(), "database");
        List<String> tables = normalizeIdentifiers(spec.tableInclude(), "table");
        validateScope(scope, databases, tables);
        Map<String, String> options = normalizeOptions(spec.options());

        LakeCatalogDesiredSpec normalized = new LakeCatalogDesiredSpec(
                catalogName,
                spec.sourceDataSourceId(),
                sourceRevision,
                adapter,
                scope,
                jdbcUrl,
                driverUrl,
                driverClass,
                checksum.toLowerCase(Locale.ROOT),
                registryRevision,
                credentialRevision,
                databases,
                tables,
                options);
        if (driverRegistry != null) {
            LakeJdbcDriverRegistry.DriverRegistration registration =
                    driverRegistry.require(adapter);
            if (!Objects.equals(driverUrl, registration.url())
                    || !Objects.equals(driverClass, registration.driverClass())
                    || !Objects.equals(checksum.toLowerCase(Locale.ROOT),
                    safeLower(registration.checksum()))
                    || !Objects.equals(registryRevision, registration.registryRevision())) {
                throw invalid("driver facts do not match server registry");
            }
        }
        return normalized;
    }

    private static void validateScope(
            LakeCatalogScope scope,
            List<String> databases,
            List<String> tables) {
        switch (scope) {
            case ALL -> {
                if (!databases.isEmpty() || !tables.isEmpty()) {
                    throw invalid("ALL scope cannot specify database or table lists");
                }
            }
            case DATABASE -> {
                if (databases.size() != 1 || !tables.isEmpty()) {
                    throw invalid("DATABASE scope requires exactly one database");
                }
            }
            case TABLE -> {
                if (databases.size() != 1 || tables.isEmpty()) {
                    throw invalid("TABLE scope requires one database and at least one table");
                }
            }
        }
    }

    private static Map<String, String> normalizeOptions(Map<String, String> source) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (source == null) {
            return Map.of();
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = normalizeOptionKey(entry.getKey());
            if (!OPTIONS.contains(key) || CatalogPropertyRedactor.isSensitiveKey(key)) {
                throw invalid("unsupported catalog option");
            }
            if (normalized.containsKey(key)) {
                throw invalid("duplicate catalog option");
            }
            String value = required(entry.getValue(), "catalog option value");
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }

    private static String normalizeOptionKey(String key) {
        if (StringUtils.isBlank(key)) {
            throw invalid("catalog option key");
        }
        return key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static List<String> normalizeIdentifiers(List<String> values, String kind) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = normalizeIdentifier(value, kind);
            if (result.contains(normalized)) {
                throw invalid("duplicate " + kind);
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String normalizeIdentifier(String value, String kind) {
        if (StringUtils.isBlank(value)) {
            throw invalid(kind);
        }
        try {
            // External JDBC names are case-sensitive in Doris include lists;
            // validate the safe identifier grammar without lower-casing them.
            return DorisIdentifier.validate(value).trim();
        } catch (RuntimeException exception) {
            throw invalid(kind);
        }
    }

    private static String normalizeCatalogName(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("catalog name");
        }
        try {
            return DorisIdentifier.normalize(value);
        } catch (RuntimeException exception) {
            throw invalid("catalog name");
        }
    }

    private static void validateJdbcEndpoint(String jdbcUrl) {
        if (!jdbcUrl.regionMatches(true, 0, "jdbc:", 0, "jdbc:".length())
                || containsCredential(jdbcUrl)) {
            throw invalid("JDBC endpoint");
        }
    }

    private static void validateDriverUrl(String driverUrl) {
        if (containsCredential(driverUrl) || driverUrl.contains("\n") || driverUrl.contains("\r")) {
            throw invalid("driver URL");
        }
    }

    private static boolean containsCredential(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(?:password|passwd|pwd|secret|token)\\s*[=:].*")
                || normalized.matches("jdbc:[^:]+://[^/@:]+:[^/@]+@.*");
    }

    private static String required(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw invalid(field);
        }
        return value.trim();
    }

    private static String safeLower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid logical JDBC catalog " + field);
    }
}
