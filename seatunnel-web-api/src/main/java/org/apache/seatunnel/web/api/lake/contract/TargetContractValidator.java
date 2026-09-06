package org.apache.seatunnel.web.api.lake.contract;

import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.common.enums.LakeTableModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates and normalises the structural invariants of TargetContract v2. */
public final class TargetContractValidator {

    private static final Set<String> GRANULARITIES = Set.of("DAY", "MONTH", "YEAR");

    private TargetContractValidator() {
    }

    /** Validates a contract without exposing a mutable normalised copy. */
    public static void validate(TargetContract contract) {
        validateAndNormalize(contract);
    }

    /**
     * Returns a defensive, canonical-ready copy.  Target identifiers are
     * lower-cased because Doris comparisons in this control plane use the
     * actual case-insensitive name semantics.
     */
    public static TargetContract validateAndNormalize(TargetContract contract) {
        if (contract == null || !Objects.equals(contract.getVersion(), TargetContract.CURRENT_VERSION)) {
            throw new IllegalArgumentException("TargetContract version must be 2");
        }
        if (contract.getTableModel() == null) {
            throw new IllegalArgumentException("TargetContract table model must be specified");
        }
        List<TargetColumn> sourceColumns = contract.getColumns();
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            throw new IllegalArgumentException("TargetContract must contain columns");
        }

        Map<String, TargetColumn> byName = new LinkedHashMap<>();
        Set<Integer> sourceOrdinals = new HashSet<>();
        Set<Integer> physicalOrdinals = new HashSet<>();
        List<TargetColumn> columns = new ArrayList<>(sourceColumns.size());
        for (TargetColumn source : sourceColumns) {
            if (source == null || source.getSourceName() == null || source.getSourceName().isBlank()
                    || source.getSourceOrdinal() == null || source.getSourceOrdinal() <= 0
                    || source.getTargetName() == null || source.getTargetName().isBlank()
                    || source.getTargetType() == null || source.getNullable() == null
                    || source.getKey() == null || source.getPhysicalOrdinal() == null
                    || source.getPhysicalOrdinal() <= 0) {
                throw new IllegalArgumentException("TargetContract column is incomplete");
            }
            if (!sourceOrdinals.add(source.getSourceOrdinal())) {
                throw new IllegalArgumentException("Duplicate source ordinal in TargetContract");
            }
            if (!physicalOrdinals.add(source.getPhysicalOrdinal())) {
                throw new IllegalArgumentException("Duplicate physical ordinal in TargetContract");
            }
            String targetName = DorisIdentifier.normalize(source.getTargetName());
            if (byName.put(targetName, source) != null) {
                throw new IllegalArgumentException("Duplicate target column name in TargetContract");
            }
            TargetType type = source.getTargetType().canonicalCopy();
            columns.add(new TargetColumn(source.getSourceName().trim(), source.getSourceOrdinal(), targetName,
                    type, source.getNullable(), source.getKey(), source.getPhysicalOrdinal()));
        }
        for (int ordinal = 1; ordinal <= columns.size(); ordinal++) {
            if (!physicalOrdinals.contains(ordinal)) {
                throw new IllegalArgumentException("Physical ordinals must be contiguous");
            }
        }
        columns.sort(Comparator.comparing(TargetColumn::getPhysicalOrdinal));

        List<String> keyColumns = normalizeNames(contract.getKeyColumns(), "key column");
        if (keyColumns.isEmpty()) {
            throw new IllegalArgumentException("TargetContract must contain at least one key column");
        }
        Set<String> keySet = new HashSet<>(keyColumns);
        if (keySet.size() != keyColumns.size()) {
            throw new IllegalArgumentException("Duplicate key column in TargetContract");
        }
        for (String key : keyColumns) {
            TargetColumn column = byName.get(key);
            if (column == null) {
                throw new IllegalArgumentException("Key column is not declared");
            }
            if (!Boolean.TRUE.equals(column.getKey())) {
                throw new IllegalArgumentException("Key column flag does not match keyColumns");
            }
            if (Boolean.TRUE.equals(column.getNullable())) {
                throw new IllegalArgumentException("Key columns must be NOT NULL");
            }
            if (column.getTargetType().getBase().isKeyForbidden()) {
                throw new IllegalArgumentException("Doris key type is not supported");
            }
        }
        for (TargetColumn column : columns) {
            boolean declaredKey = keySet.contains(column.getTargetName());
            if (declaredKey != Boolean.TRUE.equals(column.getKey())) {
                throw new IllegalArgumentException("Key column flag does not match keyColumns");
            }
            if (column.getPhysicalOrdinal() <= keyColumns.size() && !declaredKey) {
                throw new IllegalArgumentException("Key columns must be the first physical columns");
            }
            if (column.getPhysicalOrdinal() > keyColumns.size() && declaredKey) {
                throw new IllegalArgumentException("Key columns must be the first physical columns");
            }
        }
        List<String> physicalKeys = columns.stream()
                .filter(column -> Boolean.TRUE.equals(column.getKey()))
                .map(TargetColumn::getTargetName)
                .toList();
        if (!physicalKeys.equals(keyColumns)) {
            throw new IllegalArgumentException("keyColumns order must match physical key order");
        }

        TargetPartition partition = normalizePartition(contract.getPartition(), contract.getTableModel(),
                keyColumns, byName);
        TargetDistribution distribution = normalizeDistribution(
                contract.getDistribution(), contract.getTableModel(), keyColumns, byName);
        return new TargetContract(TargetContract.CURRENT_VERSION, contract.getTableModel(), columns,
                keyColumns, partition, distribution);
    }

    /**
     * Validates the source-side nullable bit required by lifecycle partition
     * policy.  Source metadata is intentionally not persisted in the target
     * contract, so the caller supplies this fact from its fresh OM snapshot.
     */
    public static void validateLifecyclePartition(TargetContract contract, boolean sourceNullable) {
        TargetContract normalised = validateAndNormalize(contract);
        if (Boolean.TRUE.equals(normalised.getPartition().getEnabled()) && sourceNullable) {
            throw new IllegalArgumentException("Lifecycle partition source column must be NOT NULL");
        }
    }

    private static TargetPartition normalizePartition(TargetPartition source, LakeTableModel tableModel,
                                                       List<String> keyColumns,
                                                       Map<String, TargetColumn> byName) {
        if (source == null || !Boolean.TRUE.equals(source.getEnabled())) {
            if (source != null && (source.getColumn() != null || source.getGranularity() != null)) {
                throw new IllegalArgumentException("Disabled partition must not declare a column");
            }
            return TargetPartition.disabled();
        }
        String column = normalizeName(source.getColumn(), "partition column");
        TargetColumn partitionColumn = byName.get(column);
        if (partitionColumn == null) {
            throw new IllegalArgumentException("Partition column is not declared");
        }
        if (tableModel == LakeTableModel.UNIQUE && !keyColumns.contains(column)) {
            throw new IllegalArgumentException("Unique lifecycle partition column must be a key column");
        }
        DorisTypeBase base = partitionColumn.getTargetType().getBase().canonical();
        if (base != DorisTypeBase.DATE && base != DorisTypeBase.DATETIME) {
            throw new IllegalArgumentException("Lifecycle partition requires DATE or DATETIME");
        }
        if (Boolean.TRUE.equals(partitionColumn.getNullable())) {
            throw new IllegalArgumentException("Lifecycle partition target column must be NOT NULL");
        }
        String granularity = source.getGranularity() == null ? null
                : source.getGranularity().trim().toUpperCase(Locale.ROOT);
        if (!GRANULARITIES.contains(granularity)) {
            throw new IllegalArgumentException("Unsupported auto partition granularity");
        }
        return TargetPartition.autoRange(column, granularity);
    }

    private static TargetDistribution normalizeDistribution(TargetDistribution source,
                                                             LakeTableModel tableModel,
                                                             List<String> keyColumns,
                                                             Map<String, TargetColumn> byName) {
        TargetDistribution effective = source;
        if (effective == null) {
            effective = tableModel == LakeTableModel.UNIQUE
                    ? TargetDistribution.hash(keyColumns) : TargetDistribution.random();
        }
        String type = effective.getType() == null ? null
                : effective.getType().trim().toUpperCase(Locale.ROOT);
        if (!TargetDistribution.RANDOM.equals(type) && !TargetDistribution.HASH.equals(type)) {
            throw new IllegalArgumentException("Unsupported Doris distribution type");
        }
        List<String> columns = normalizeNames(effective.getColumns(), "distribution column");
        if (TargetDistribution.RANDOM.equals(type) && !columns.isEmpty()) {
            throw new IllegalArgumentException("Random distribution must not declare columns");
        }
        for (String column : columns) {
            if (!byName.containsKey(column)) {
                throw new IllegalArgumentException("Distribution column is not declared");
            }
        }
        if (TargetDistribution.HASH.equals(type) && columns.isEmpty()) {
            throw new IllegalArgumentException("Hash distribution requires columns");
        }
        if (tableModel == LakeTableModel.UNIQUE && TargetDistribution.HASH.equals(type)
                && !new HashSet<>(keyColumns).containsAll(columns)) {
            throw new IllegalArgumentException("Unique hash distribution must use key columns");
        }
        String buckets = effective.getBuckets() == null ? TargetDistribution.AUTO : effective.getBuckets().trim();
        if (TargetDistribution.AUTO.equalsIgnoreCase(buckets)) {
            buckets = TargetDistribution.AUTO;
        } else {
            try {
                int parsed = Integer.parseInt(buckets);
                if (parsed <= 0) {
                    throw new NumberFormatException();
                }
                buckets = Integer.toString(parsed);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Doris bucket count must be AUTO or a positive integer");
            }
        }
        return new TargetDistribution(type, columns, buckets);
    }

    private static List<String> normalizeNames(List<String> names, String label) {
        if (names == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(names.size());
        for (String name : names) {
            normalized.add(normalizeName(name, label));
        }
        return List.copyOf(normalized);
    }

    private static String normalizeName(String name, String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("TargetContract " + label + " must not be blank");
        }
        return DorisIdentifier.normalize(name);
    }
}
