package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON/SHA-256 identity for a non-secret logical catalog spec. */
public final class LakeCatalogDesiredSpecCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT);

    private LakeCatalogDesiredSpecCanonicalizer() {
    }

    public static String canonicalJson(LakeCatalogDesiredSpec spec) {
        LakeCatalogDesiredSpec normalized =
                LakeCatalogDesiredSpecValidator.validateAndNormalize(spec);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("adapter", normalized.adapter().code());
        root.put("catalogName", normalized.catalogName());
        root.put("credentialRevision", normalized.credentialRevision());
        root.put("databaseInclude", normalized.databaseInclude());
        root.put("driverChecksum", normalized.driverChecksum());
        root.put("driverClass", normalized.driverClass());
        root.put("driverRegistryRevision", normalized.driverRegistryRevision());
        root.put("driverUrl", normalized.driverUrl());
        root.put("jdbcUrl", normalized.jdbcUrl());
        root.put("options", new TreeMap<>(normalized.options()));
        root.put("scope", normalized.scope().name());
        root.put("sourceDataSourceId", normalized.sourceDataSourceId());
        root.put("sourceDataSourceRevision", normalized.sourceDataSourceRevision());
        root.put("tableInclude", normalized.tableInclude());
        root.put("type", "jdbc");
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Logical JDBC catalog spec cannot be canonicalized");
        }
    }

    public static String sha256(LakeCatalogDesiredSpec spec) {
        return sha256(canonicalJson(spec));
    }

    public static String canonicalHash(LakeCatalogDesiredSpec spec) {
        return sha256(spec);
    }

    public static String sha256(String canonicalJson) {
        if (canonicalJson == null) {
            throw new IllegalArgumentException("Canonical catalog JSON must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    /** Web-owned actual keys used by drift comparison; Doris defaults are ignored. */
    public static Map<String, String> webOwnedActualProperties(Map<String, String> actual) {
        if (actual == null || actual.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim()
                    .toLowerCase(Locale.ROOT).replace('-', '_');
            if (isWebOwnedProperty(key) && entry.getValue() != null) {
                result.put(key, entry.getValue().trim());
            }
        }
        return Map.copyOf(result);
    }

    private static boolean isWebOwnedProperty(String key) {
        return switch (key) {
            case "type", "jdbc_url", "driver_url", "driver_class",
                    "only_specified_database", "include_database_list", "include_table_list",
                    "lower_case_table_names", "lower_case_database_names" -> true;
            default -> false;
        };
    }
}
