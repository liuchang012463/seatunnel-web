package org.apache.seatunnel.web.api.lake.doris;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Narrow allowlist for properties emitted by the physical table DDL builder. */
public final class DorisTablePropertyWhitelist {

    private static final Set<String> ALLOWED = Set.of(
            "partition.retention_count",
            "_auto_bucket",
            "enable_unique_key_merge_on_write",
            "storage_medium",
            "storage_format",
            "inverted_index_storage_format",
            "light_schema_change",
            "disable_auto_compaction",
            "group_commit_interval_ms",
            "group_commit_data_bytes",
            "replication_num",
            "replication_allocation"
    );

    /** Properties that the lifecycle reconciler is allowed to change in P0. */
    private static final Set<String> ALTER_ALLOWED = Set.of("partition.retention_count");

    private DorisTablePropertyWhitelist() {
    }

    public static boolean isAllowed(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return ALLOWED.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    public static Set<String> allowedKeys() {
        return Set.copyOf(ALLOWED);
    }

    public static Map<String, String> validateAndCopy(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Doris table property key must not be blank");
            }
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED.contains(key)) {
                throw new IllegalArgumentException("Unsupported Doris table property");
            }
            if (result.put(key, entry.getValue()) != null) {
                throw new IllegalArgumentException("Duplicate Doris table property");
            }
            validateValue(key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Validates the deliberately smaller ALTER TABLE surface.  CREATE TABLE
     * accepts a few more immutable/runtime properties, but allowing those to
     * be changed by reconciliation would make the operation unsafe.
     */
    public static Map<String, String> validateAlterAndCopy(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("Doris table property update must not be empty");
        }
        Map<String, String> validated = validateAndCopy(properties);
        if (!ALTER_ALLOWED.containsAll(validated.keySet())) {
            throw new IllegalArgumentException("Unsupported Doris table property update");
        }
        return validated;
    }

    private static void validateValue(String key, String value) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Doris table property value is invalid");
        }
        if (key.equals("partition.retention_count")) {
            try {
                if (Integer.parseInt(value.trim()) <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("partition.retention_count must be positive", exception);
            }
        }
        if (key.equals("_auto_bucket") || key.equals("enable_unique_key_merge_on_write")
                || key.equals("light_schema_change") || key.equals("disable_auto_compaction")) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Doris boolean table property is invalid");
            }
        }
    }
}
