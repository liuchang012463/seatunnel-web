package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.seatunnel.web.api.lake.CatalogPropertyRedactor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Compares only the properties owned by the Web logical-catalog contract.
 * Doris may add defaults or rewrite connector metadata; those values are
 * filtered before comparison.  An ambiguous JDBC URL observation is UNKNOWN,
 * never a false drift signal.
 */
public final class LakeCatalogActualEvaluator {

    /** Compares an already-read SHOW CATALOG property map with desired state. */
    public LakeCatalogValidationResult evaluate(
            LakeCatalogDesiredSpec desiredSpec,
            Map<String, String> actualProperties) {
        final LakeCatalogDesiredSpec desired;
        try {
            desired = LakeCatalogDesiredSpecValidator.validateAndNormalize(desiredSpec);
        } catch (RuntimeException exception) {
            return LakeCatalogValidationResult.unknown(LakeCatalogValidationCode.INPUT_INVALID);
        }

        Map<String, String> actual = LakeCatalogDesiredSpecCanonicalizer
                .webOwnedActualProperties(actualProperties, desired);
        Map<String, String> expected = LakeCatalogDesiredSpecCanonicalizer
                .webOwnedDesiredProperties(desired);
        Map<String, String> mismatches = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String key = entry.getKey();
            String expectedValue = entry.getValue();
            if (!actual.containsKey(key) || actual.get(key) == null
                    || actual.get(key).isBlank()) {
                return new LakeCatalogValidationResult(
                        LakeCatalogValidationStatus.UNKNOWN,
                        LakeCatalogValidationCode.REQUIRED_PROPERTY_UNKNOWN,
                        actual,
                        Map.of());
            }
            String actualValue = actual.get(key);
            if ("jdbc_url".equals(key)) {
                if (sameJdbcUrl(expectedValue, actualValue)) {
                    continue;
                }
                if (jdbcBase(expectedValue).equals(jdbcBase(actualValue))
                        && !isMasked(actualValue)) {
                    return new LakeCatalogValidationResult(
                            LakeCatalogValidationStatus.UNKNOWN,
                            LakeCatalogValidationCode.JDBC_URL_AMBIGUOUS,
                            actual,
                            Map.of());
                }
                if (isMasked(actualValue)) {
                    return new LakeCatalogValidationResult(
                            LakeCatalogValidationStatus.UNKNOWN,
                            LakeCatalogValidationCode.JDBC_URL_AMBIGUOUS,
                            actual,
                            Map.of());
                }
                mismatches.put(key, CatalogPropertyRedactor.MASK);
                continue;
            }
            if (!sameValue(key, expectedValue, actualValue)) {
                // Keep only the key in diagnostics. The actual value may have
                // been supplied by Doris rather than the Web desired spec.
                mismatches.put(key, CatalogPropertyRedactor.MASK);
            }
        }
        if (!mismatches.isEmpty()) {
            return LakeCatalogValidationResult.mismatch(actual, mismatches);
        }
        return LakeCatalogValidationResult.match(actual);
    }

    /** Static convenience for pure callers and tests. */
    public static LakeCatalogValidationResult compare(
            LakeCatalogDesiredSpec desiredSpec,
            Map<String, String> actualProperties) {
        return new LakeCatalogActualEvaluator().evaluate(desiredSpec, actualProperties);
    }

    private static boolean sameJdbcUrl(String expected, String actual) {
        String desired = trim(expected);
        String observed = trim(actual);
        return Objects.equals(desired, observed)
                || Objects.equals(dorisJdbcUrl(desired), observed);
    }

    /** Mirrors only JdbcResource.handleJdbcUrl transformations verified on Doris 4.1.2. */
    private static String dorisJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        // Do not sort, decode or discard arbitrary URL parameters: other rewrites remain UNKNOWN.
        String normalized = url.replace(" ", "");
        if (normalized.startsWith("jdbc:mysql:")) {
            normalized = forceBooleanParameter(normalized, "yearIsDateType", false);
            normalized = forceBooleanParameter(normalized, "tinyInt1isBit", false);
            normalized = forceBooleanParameter(normalized, "useUnicode", true);
            normalized = forceBooleanParameter(normalized, "rewriteBatchedStatements", true);
            if (!normalized.contains("characterEncoding=utf-8")) {
                normalized = appendParameter(normalized, "characterEncoding=utf-8");
            }
        } else if (normalized.startsWith("jdbc:postgresql:")) {
            normalized = forceBooleanParameter(normalized, "reWriteBatchedInserts", true);
        }
        return normalized;
    }

    private static String forceBooleanParameter(String url, String name, boolean value) {
        String expected = name + '=' + value;
        if (url.contains(expected)) {
            return url;
        }
        String opposite = name + '=' + !value;
        return url.contains(opposite) ? url.replace(opposite, expected) : appendParameter(url, expected);
    }

    private static String appendParameter(String url, String parameter) {
        String delimiter = url.contains("?") ? "&" : "?";
        return url + (url.endsWith(delimiter) ? "" : delimiter) + parameter;
    }

    private static String jdbcBase(String value) {
        String trimmed = trim(value);
        if (trimmed == null) {
            return "";
        }
        int query = trimmed.indexOf('?');
        int semicolon = trimmed.indexOf(';');
        int delimiter;
        if (query < 0) {
            delimiter = semicolon;
        } else if (semicolon < 0) {
            delimiter = query;
        } else {
            delimiter = Math.min(query, semicolon);
        }
        return delimiter < 0 ? trimmed : trimmed.substring(0, delimiter);
    }

    private static boolean isMasked(String value) {
        return value != null && value.contains(CatalogPropertyRedactor.MASK);
    }

    private static boolean sameValue(String key, String expected, String actual) {
        if ("type".equals(key) || "only_specified_database".equals(key)
                || key.startsWith("enable_") || key.startsWith("lower_case_")
                || "use_meta_cache".equals(key)
                || "connection_pool_keep_alive".equals(key)) {
            return expected.equalsIgnoreCase(actual);
        }
        return Objects.equals(expected, actual);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
