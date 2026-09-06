package org.apache.seatunnel.web.api.lake.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableConstraint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Canonical source schema identity for OpenMetadata 1.12.10 Table results. */
public final class SourceSchemaCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SourceSchemaCanonicalizer() {
    }

    /** Builds a snapshot that excludes owner, tags, descriptions and profiles. */
    public static SourceObjectSnapshot snapshot(OpenMetadataTable table) {
        Objects.requireNonNull(table, "table");
        String id = clean(table.getId());
        String fqn = clean(table.getFullyQualifiedName());
        List<OpenMetadataColumn> sourceColumns = table.getColumns() == null
                ? List.of() : table.getColumns();
        List<OpenMetadataTableConstraint> sourceConstraints = table.getTableConstraints() == null
                ? List.of() : table.getTableConstraints();

        List<String> notNullColumns = new ArrayList<>();
        for (OpenMetadataTableConstraint constraint : sourceConstraints) {
            if (constraint != null && isPrimaryKey(constraint.getConstraintType())) {
                for (String column : safe(constraint.getColumns())) {
                    if (column != null) {
                        notNullColumns.add(column.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        List<SourceColumnSnapshot> columns = new ArrayList<>();
        for (OpenMetadataColumn column : sourceColumns) {
            if (column == null) {
                continue;
            }
            String name = clean(column.getName());
            if (name == null || name.isBlank()) {
                continue;
            }
            String constraint = normalizeConstraint(column.getConstraint());
            Boolean nullable = nullable(constraint);
            if (notNullColumns.contains(name.toLowerCase(Locale.ROOT))) {
                nullable = false;
            }
            columns.add(new SourceColumnSnapshot(
                    name,
                    column.getOrdinalPosition(),
                    normalizeType(column.getDataType()),
                    clean(column.getDataTypeDisplay()),
                    column.getDataLength(),
                    column.getPrecision(),
                    column.getScale(),
                    constraint,
                    nullable));
        }
        columns.sort(Comparator
                .comparing(SourceColumnSnapshot::ordinal,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SourceColumnSnapshot::name));

        List<SourceConstraintSnapshot> constraints = new ArrayList<>();
        for (OpenMetadataTableConstraint constraint : sourceConstraints) {
            if (constraint == null) {
                continue;
            }
            constraints.add(new SourceConstraintSnapshot(
                    normalizeConstraint(constraint.getConstraintType()),
                    normalizeNames(constraint.getColumns()),
                    normalizeNames(constraint.getReferredColumns()),
                    normalizeConstraint(constraint.getRelationshipType())));
        }
        constraints.sort(Comparator.comparing(SourceSchemaCanonicalizer::constraintKey));

        String canonicalJson = canonicalJson(columns, constraints);
        String snapshotJson = snapshotJson(id, fqn, columns, constraints);
        return new SourceObjectSnapshot(id, fqn, columns, constraints,
                sha256(canonicalJson), snapshotJson);
    }

    public static String canonicalJson(OpenMetadataTable table) {
        SourceObjectSnapshot snapshot = snapshotWithoutHash(table);
        return canonicalJson(snapshot.columns(), snapshot.constraints());
    }

    public static String canonicalHash(OpenMetadataTable table) {
        return sha256(canonicalJson(table));
    }

    private static SourceObjectSnapshot snapshotWithoutHash(OpenMetadataTable table) {
        Objects.requireNonNull(table, "table");
        // snapshot() is intentionally the single normalization path. The
        // temporary object does not recurse through canonicalJson().
        return snapshot(table);
    }

    private static String canonicalJson(
            List<SourceColumnSnapshot> columns, List<SourceConstraintSnapshot> constraints) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("columns", columns.stream().map(SourceSchemaCanonicalizer::columnMap).toList());
        root.put("constraints", constraints.stream()
                .map(SourceSchemaCanonicalizer::constraintMap).toList());
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Source schema cannot be canonicalized", exception);
        }
    }

    private static String snapshotJson(
            String id, String fqn, List<SourceColumnSnapshot> columns,
            List<SourceConstraintSnapshot> constraints) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("objectType", "TABLE");
        if (id == null) {
            root.putNull("omEntityId");
        } else {
            root.put("omEntityId", id);
        }
        if (fqn == null) {
            root.putNull("omFqn");
        } else {
            root.put("omFqn", fqn);
        }
        try {
            root.set("schema", MAPPER.readTree(canonicalJson(columns, constraints)));
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Source snapshot cannot be serialized", exception);
        }
    }

    private static Map<String, Object> columnMap(SourceColumnSnapshot column) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", column.name());
        value.put("ordinal", column.ordinal());
        value.put("dataType", column.dataType());
        value.put("dataTypeDisplay", column.dataTypeDisplay());
        value.put("dataLength", column.dataLength());
        value.put("precision", column.precision());
        value.put("scale", column.scale());
        value.put("constraint", column.constraint());
        value.put("nullable", column.nullable());
        return value;
    }

    private static Map<String, Object> constraintMap(SourceConstraintSnapshot constraint) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("constraintType", constraint.constraintType());
        value.put("columns", constraint.columns());
        value.put("referredColumns", constraint.referredColumns());
        value.put("relationshipType", constraint.relationshipType());
        return value;
    }

    private static String constraintKey(SourceConstraintSnapshot constraint) {
        return String.valueOf(constraint.constraintType()) + "|"
                + String.join(",", constraint.columns()) + "|"
                + String.join(",", constraint.referredColumns()) + "|"
                + String.valueOf(constraint.relationshipType());
    }

    private static List<String> normalizeNames(List<String> names) {
        List<String> result = new ArrayList<>();
        for (String name : safe(names)) {
            String normalized = clean(name);
            if (normalized != null && !normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private static Boolean nullable(String constraint) {
        if (constraint == null) {
            return null;
        }
        return switch (constraint) {
            case "PRIMARY_KEY", "NOT_NULL" -> false;
            case "NULL", "NULLABLE" -> true;
            default -> null;
        };
    }

    private static boolean isPrimaryKey(String constraint) {
        return "PRIMARY_KEY".equals(normalizeConstraint(constraint));
    }

    private static String normalizeType(String type) {
        String value = clean(type);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String normalizeConstraint(String value) {
        String normalized = clean(value);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public static String sha256(String canonicalJson) {
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
