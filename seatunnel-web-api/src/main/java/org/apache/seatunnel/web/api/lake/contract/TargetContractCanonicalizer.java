package org.apache.seatunnel.web.api.lake.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Produces the stable JSON and SHA-256 identity of a TargetContract v2.
 *
 * <p>The hash is deliberately limited to structure observable in Doris
 * metadata.  Source names and source ordinals belong to the source schema
 * hash and field-mapping binding; including them here would make a renamed
 * target column fail an otherwise successful CREATE/SHOW CREATE comparison,
 * because Doris does not retain those source facts.</p>
 */
public final class TargetContractCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT);

    private TargetContractCanonicalizer() {
    }

    public static String canonicalJson(TargetContract contract) {
        TargetContract normalised = TargetContractValidator.validateAndNormalize(contract);
        try {
            return MAPPER.writeValueAsString(toCanonicalMap(normalised));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("TargetContract cannot be canonicalized", exception);
        }
    }

    public static String sha256(TargetContract contract) {
        return sha256(canonicalJson(contract));
    }

    public static String canonicalHash(TargetContract contract) {
        return sha256(contract);
    }

    public static String sha256(String canonicalJson) {
        if (canonicalJson == null) {
            throw new IllegalArgumentException("Canonical JSON must not be null");
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
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Map<String, Object> toCanonicalMap(TargetContract contract) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", contract.getVersion());
        root.put("tableModel", contract.getTableModel().name());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (TargetColumn column : contract.getColumns()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("targetName", column.getTargetName());
            value.put("targetType", typeMap(column.getTargetType()));
            value.put("nullable", column.getNullable());
            value.put("key", column.getKey());
            value.put("physicalOrdinal", column.getPhysicalOrdinal());
            columns.add(value);
        }
        root.put("columns", columns);
        root.put("keyColumns", List.copyOf(contract.getKeyColumns()));

        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("enabled", contract.getPartition().getEnabled());
        partition.put("column", contract.getPartition().getColumn());
        partition.put("granularity", contract.getPartition().getGranularity());
        root.put("partition", partition);

        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("type", contract.getDistribution().getType());
        distribution.put("columns", List.copyOf(contract.getDistribution().getColumns()));
        distribution.put("buckets", contract.getDistribution().getBuckets());
        root.put("distribution", distribution);
        return root;
    }

    private static Map<String, Object> typeMap(TargetType type) {
        TargetType canonical = type.canonicalCopy();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("base", canonical.getBase().name());
        if (canonical.getLength() != null) {
            value.put("length", canonical.getLength());
        }
        if (canonical.getPrecision() != null) {
            value.put("precision", canonical.getPrecision());
        }
        if (canonical.getScale() != null) {
            value.put("scale", canonical.getScale());
        }
        return value;
    }
}
