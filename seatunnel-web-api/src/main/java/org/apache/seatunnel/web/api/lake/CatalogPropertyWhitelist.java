package org.apache.seatunnel.web.api.lake;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Allowlist for Doris external-catalog properties.
 *
 * <p>Catalog adapters select from this list.  Callers must not pass arbitrary
 * UI keys through to a Doris {@code CREATE CATALOG} statement.</p>
 */
public final class CatalogPropertyWhitelist {

    private static final Set<String> COMMON = Set.of(
            "type",
            "user",
            "password",
            "jdbc_url",
            "driver_url",
            "driver_class",
            "checksum",
            "only_specified_database",
            "include_database_list",
            "include_table_list",
            "only_known",
            "use_meta_cache",
            "enable_meta_cache",
            "meta_cache_expiration_second",
            "enable_partition_cache",
            "connection_pool_min_size",
            "connection_pool_max_size",
            "connection_pool_max_wait_time",
            "connection_pool_max_life_time",
            "connection_pool_keep_alive",
            "lower_case_table_names",
            "lower_case_database_names",
            "lower_case_meta_names"
    );

    private CatalogPropertyWhitelist() {
    }

    public static boolean isAllowed(String key) {
        return isAllowed(null, key);
    }

    public static boolean isAllowed(String adapter, String key) {
        if (key == null) {
            return false;
        }
        String normalized = normalize(key);
        // Adapter-specific additions can be introduced here without opening an
        // arbitrary property passthrough. The v1.4 P0 adapters use COMMON.
        return COMMON.contains(normalized);
    }

    public static Set<String> allowedKeys(String adapter) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(COMMON));
    }

    /** Validates and copies properties, normalising their key spelling. */
    public static Map<String, String> validateAndCopy(Map<String, String> properties) {
        return validateAndCopy(null, properties);
    }

    public static Map<String, String> validateAndCopy(String adapter, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = normalize(entry.getKey());
            if (!isAllowed(adapter, key)) {
                throw new IllegalArgumentException("Unsupported Doris catalog property");
            }
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate Doris catalog property");
            }
            result.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    public static String normalize(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Catalog property key must not be blank");
        }
        return key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
