package org.apache.seatunnel.web.api.lake.table;

import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractValidator;
import org.apache.seatunnel.web.api.lake.contract.TargetDistribution;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.contract.TargetType;
import org.apache.seatunnel.web.api.lake.source.SourceColumnSnapshot;
import org.apache.seatunnel.web.api.lake.source.SourceConstraintSnapshot;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.common.enums.LakeTableModel;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableColumnDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableDistributionDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePartitionDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePreviewDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds the v2 target contract from structured fields and a fresh source snapshot. */
public final class LakeManagedTableContractFactory {

    /**
     * Builds a defensive, validated contract.  Source ordinal, source type
     * and source nullability always come from {@code source}; request fields
     * only select target names/types, keys, partition and distribution.
     */
    public TargetContract build(SourceObjectSnapshot source, LakeManagedTablePreviewDTO request) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(request, "request");
        List<SourceColumnSnapshot> sourceColumns = source.columns();
        if (sourceColumns.isEmpty()) {
            throw new IllegalArgumentException("OpenMetadata table has no columns");
        }

        Map<String, LakeManagedTableColumnDTO> overrides = columnOverrides(request.getColumns());
        List<String> primaryKeys = primaryKeyNames(source.constraints());
        Set<String> requestedKeys = requestedKeys(request, primaryKeys, overrides);
        if (requestedKeys.isEmpty()) {
            throw new IllegalArgumentException("MANAGED table requires at least one key column");
        }

        Map<String, TargetColumn> bySource = new LinkedHashMap<>();
        for (SourceColumnSnapshot sourceColumn : sourceColumns) {
            if (sourceColumn == null || sourceColumn.name() == null || sourceColumn.name().isBlank()) {
                continue;
            }
            String sourceName = sourceColumn.name().trim();
            String sourceKey = normalizeSource(sourceName);
            LakeManagedTableColumnDTO override = overrides.get(sourceKey);
            boolean key = requestedKeys.contains(sourceKey);
            if (override != null && override.getKey() != null
                    && override.getKey() != key) {
                throw new IllegalArgumentException("Column key flag does not match keyColumns");
            }
            String targetName = override == null || isBlank(override.getTargetField())
                    ? DorisIdentifier.normalize(sourceName) : DorisIdentifier.normalize(override.getTargetField());
            TargetType targetType = targetType(override == null ? null : override.getTargetType(), key);
            boolean nullable = !Boolean.FALSE.equals(sourceColumn.nullable());
            if (key && nullable) {
                throw new IllegalArgumentException("Key columns must be NOT NULL in the source");
            }
            TargetColumn targetColumn = new TargetColumn(
                    sourceName,
                    sourceColumn.ordinal() == null || sourceColumn.ordinal() <= 0
                            ? bySource.size() + 1 : sourceColumn.ordinal(),
                    targetName,
                    targetType,
                    nullable,
                    key,
                    0);
            if (bySource.put(sourceKey, targetColumn) != null) {
                throw new IllegalArgumentException("Duplicate source column name");
            }
        }

        if (!overrides.keySet().stream().allMatch(bySource::containsKey)) {
            throw new IllegalArgumentException("Target mapping contains an unknown source column");
        }
        List<String> keyOrder = keyOrder(request, primaryKeys, requestedKeys, bySource, overrides);
        List<TargetColumn> physicalColumns = new ArrayList<>(bySource.size());
        int physicalOrdinal = 1;
        for (String key : keyOrder) {
            TargetColumn column = bySource.get(key);
            if (column == null) {
                throw new IllegalArgumentException("Key column is not declared by the source");
            }
            physicalColumns.add(copyWithPhysicalOrdinal(column, physicalOrdinal++));
        }
        List<TargetColumn> valueColumns = bySource.values().stream()
                .filter(column -> !Boolean.TRUE.equals(column.getKey()))
                .sorted(Comparator.comparing(TargetColumn::getSourceOrdinal)
                        .thenComparing(TargetColumn::getSourceName))
                .toList();
        for (TargetColumn column : valueColumns) {
            physicalColumns.add(copyWithPhysicalOrdinal(column, physicalOrdinal++));
        }

        List<String> targetKeyOrder = keyOrder.stream()
                .map(bySource::get)
                .map(TargetColumn::getTargetName)
                .toList();

        TargetContract contract = new TargetContract(
                request.getTableModel() == null ? LakeTableModel.DUPLICATE : request.getTableModel(),
                physicalColumns,
                targetKeyOrder,
                partition(request.getPartition()),
                distribution(request.getDistribution()));
        TargetContract normalized = TargetContractValidator.validateAndNormalize(contract);
        validateSourcePartition(normalized, sourceColumns);
        return normalized;
    }

    /** Ensures the signed preview contract still describes the fresh OM source. */
    public void validateAgainstSource(TargetContract contract, SourceObjectSnapshot source) {
        TargetContract normalized = TargetContractValidator.validateAndNormalize(contract);
        Map<String, SourceColumnSnapshot> sourceColumns = new HashMap<>();
        for (SourceColumnSnapshot column : source.columns()) {
            if (column != null && column.name() != null && !column.name().isBlank()) {
                sourceColumns.put(normalizeSource(column.name()), column);
            }
        }
        if (sourceColumns.size() != normalized.getColumns().size()) {
            throw new IllegalArgumentException("Target contract does not match the current source schema");
        }
        for (TargetColumn column : normalized.getColumns()) {
            SourceColumnSnapshot sourceColumn = sourceColumns.get(normalizeSource(column.getSourceName()));
            if (sourceColumn == null
                    || !Objects.equals(effectiveOrdinal(sourceColumn), column.getSourceOrdinal())
                    || !Objects.equals(!Boolean.FALSE.equals(sourceColumn.nullable()), column.getNullable())) {
                throw new IllegalArgumentException("Target contract does not match the current source schema");
            }
        }
        validateSourcePartition(normalized, source.columns());
    }

    public List<LakeManagedTableFieldMapping> fieldMappings(TargetContract contract) {
        TargetContract normalized = TargetContractValidator.validateAndNormalize(contract);
        return normalized.getColumns().stream()
                .sorted(Comparator.comparing(TargetColumn::getSourceOrdinal))
                .map(column -> new LakeManagedTableFieldMapping(
                        column.getSourceName(), column.getTargetName(), renderType(column.getTargetType())))
                .toList();
    }

    public String renderType(TargetType source) {
        TargetType type = source.canonicalCopy();
        DorisTypeBase base = type.getBase();
        return switch (base) {
            case VARCHAR, CHAR -> base.name() + "(" + type.getLength() + ")";
            case DECIMAL -> "DECIMAL(" + type.getPrecision() + "," + type.getScale() + ")";
            case DATETIME -> type.getScale() == null || type.getScale() == 0
                    ? "DATETIME" : "DATETIME(" + type.getScale() + ")";
            case TEXT -> "STRING";
            default -> base.name();
        };
    }

    private static Map<String, LakeManagedTableColumnDTO> columnOverrides(
            List<LakeManagedTableColumnDTO> requested) {
        Map<String, LakeManagedTableColumnDTO> result = new LinkedHashMap<>();
        if (requested == null) {
            return result;
        }
        for (LakeManagedTableColumnDTO column : requested) {
            if (column == null || isBlank(column.getSourceField())) {
                throw new IllegalArgumentException("Target mapping source field must not be blank");
            }
            if (result.put(normalizeSource(column.getSourceField()), column) != null) {
                throw new IllegalArgumentException("Duplicate source field mapping");
            }
        }
        return result;
    }

    private static Set<String> requestedKeys(
            LakeManagedTablePreviewDTO request,
            List<String> primaryKeys,
            Map<String, LakeManagedTableColumnDTO> overrides) {
        List<String> explicit = request.getKeyColumns();
        if (explicit != null && !explicit.isEmpty()) {
            Set<String> result = new LinkedHashSetPreservingOrder();
            for (String key : explicit) {
                String sourceKey = resolveSourceKey(key, overrides);
                if (isBlank(key) || !result.add(sourceKey)) {
                    throw new IllegalArgumentException("Duplicate or blank key column");
                }
            }
            return result;
        }
        if (overrides.values().stream().anyMatch(column -> column.getKey() != null)) {
            Set<String> result = new LinkedHashSetPreservingOrder();
            overrides.forEach((name, column) -> {
                if (Boolean.TRUE.equals(column.getKey())) {
                    result.add(name);
                }
            });
            return result;
        }
        return new LinkedHashSetPreservingOrder(primaryKeys);
    }

    private static List<String> keyOrder(
            LakeManagedTablePreviewDTO request,
            List<String> primaryKeys,
            Set<String> requestedKeys,
            Map<String, TargetColumn> bySource,
            Map<String, LakeManagedTableColumnDTO> overrides) {
        List<String> order = new ArrayList<>();
        List<String> explicit = request.getKeyColumns();
        if (explicit != null && !explicit.isEmpty()) {
            explicit.forEach(item -> order.add(resolveSourceKey(item, overrides)));
        } else if (!primaryKeys.isEmpty() && requestedKeys.equals(new HashSet<>(primaryKeys))) {
            order.addAll(primaryKeys);
        } else {
            order.addAll(requestedKeys);
        }
        if (order.isEmpty() || order.stream().anyMatch(item -> !bySource.containsKey(item))) {
            throw new IllegalArgumentException("Key column is not declared by the source");
        }
        return List.copyOf(order);
    }

    private static String resolveSourceKey(
            String requested, Map<String, LakeManagedTableColumnDTO> overrides) {
        String normalized = normalizeSource(requested);
        if (overrides.containsKey(normalized)) {
            return normalized;
        }
        String resolved = null;
        for (Map.Entry<String, LakeManagedTableColumnDTO> entry : overrides.entrySet()) {
            if (entry.getValue() != null && !isBlank(entry.getValue().getTargetField())
                    && normalized.equals(normalizeSource(entry.getValue().getTargetField()))) {
                if (resolved != null) {
                    throw new IllegalArgumentException("Key column target name is ambiguous");
                }
                resolved = entry.getKey();
            }
        }
        return resolved == null ? normalized : resolved;
    }

    private static TargetColumn copyWithPhysicalOrdinal(TargetColumn source, int ordinal) {
        return new TargetColumn(source.getSourceName(), source.getSourceOrdinal().intValue(),
                source.getTargetName(), source.getTargetType(), source.getNullable().booleanValue(),
                source.getKey().booleanValue(), ordinal);
    }

    private static TargetType targetType(String type, boolean key) {
        if (isBlank(type)) {
            return key ? TargetType.varchar(255) : new TargetType(DorisTypeBase.STRING);
        }
        return TargetType.parseDorisType(type);
    }

    private static TargetPartition partition(LakeManagedTablePartitionDTO source) {
        if (source == null) {
            return TargetPartition.disabled();
        }
        return new TargetPartition(Boolean.TRUE.equals(source.getEnabled()), source.getColumn(), source.getGranularity());
    }

    private static TargetDistribution distribution(LakeManagedTableDistributionDTO source) {
        if (source == null) {
            return null;
        }
        return new TargetDistribution(source.getType(), source.getColumns(), source.getBuckets());
    }

    private static List<String> primaryKeyNames(List<SourceConstraintSnapshot> constraints) {
        List<String> names = new ArrayList<>();
        if (constraints != null) {
            for (SourceConstraintSnapshot constraint : constraints) {
                if (constraint == null || !"PRIMARY_KEY".equalsIgnoreCase(constraint.constraintType())) {
                    continue;
                }
                for (String column : constraint.columns() == null ? List.<String>of() : constraint.columns()) {
                    String normalized = normalizeSource(column);
                    if (!normalized.isBlank() && !names.contains(normalized)) {
                        names.add(normalized);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    private static void validateSourcePartition(
            TargetContract contract, List<SourceColumnSnapshot> sourceColumns) {
        if (!Boolean.TRUE.equals(contract.getPartition().getEnabled())) {
            return;
        }
        String targetName = contract.getPartition().getColumn();
        TargetColumn targetColumn = contract.getColumns().stream()
                .filter(column -> targetName.equals(column.getTargetName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Partition column is not declared"));
        SourceColumnSnapshot sourceColumn = sourceColumns.stream()
                .filter(column -> column != null && normalizeSource(column.name())
                        .equals(normalizeSource(targetColumn.getSourceName())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Partition source column is not declared"));
        if (!isDateTimeSourceType(sourceColumn.dataType())) {
            throw new IllegalArgumentException(
                    "Lifecycle partition source column must be DATE or DATETIME");
        }
        TargetContractValidator.validateLifecyclePartition(contract, Boolean.TRUE.equals(sourceColumn.nullable()));
    }

    private static boolean isDateTimeSourceType(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("DATE")
                || normalized.matches("DATETIME(?:\\s*\\(\\s*\\d+\\s*\\))?");
    }

    private static Integer effectiveOrdinal(SourceColumnSnapshot source) {
        return source.ordinal() == null || source.ordinal() <= 0 ? null : source.ordinal();
    }

    private static String normalizeSource(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** LinkedHashSet with a short descriptive name to document key order semantics. */
    private static final class LinkedHashSetPreservingOrder extends java.util.LinkedHashSet<String> {
        private LinkedHashSetPreservingOrder() {
            super();
        }

        private LinkedHashSetPreservingOrder(java.util.Collection<String> values) {
            super(values);
        }
    }
}
