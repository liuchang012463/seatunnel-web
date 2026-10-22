package org.apache.seatunnel.web.api.metadata.openlineage;

import org.apache.seatunnel.web.api.metadata.FieldMappingExtractor;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Binds every mapped column to the dataset it comes from.
 *
 * <p>
 * The column mappings of a job only carry column names, so the source table of a column has to be
 * resolved from the job configuration. Three rules are applied, in this order:
 * </p>
 * <ol>
 *   <li>the job reads exactly one table: every column belongs to it;</li>
 *   <li>the mapping comes from a SQL projection with a table alias: the alias names the table;</li>
 *   <li>the transform names its source table explicitly: that table is used.</li>
 * </ol>
 *
 * <p>
 * A column that none of these rules resolves is left out instead of being attached to a guessed
 * table: a wrong column edge is harder to clean up than a missing one. The event then keeps its
 * table level lineage, which the design accepts for multi table jobs.
 * </p>
 */
public final class OpenLineageColumnLineageBuilder {

    private OpenLineageColumnLineageBuilder() {}

    /**
     * Builds the column lineage of every output dataset that has at least one resolved column.
     */
    public static List<OpenLineageSnapshot.OutputLineage> build(
            List<OpenLineageSnapshot.Dataset> inputs,
            List<OpenLineageSnapshot.Dataset> outputs,
            FieldMappingExtractor.Result extraction) {
        if (inputs == null || inputs.isEmpty() || outputs == null || outputs.isEmpty()
                || extraction == null || extraction.mappings().isEmpty()) {
            return List.of();
        }
        Map<String, FieldMappingExtractor.Owner> owners = new LinkedHashMap<>();
        for (FieldMappingExtractor.Owner owner : extraction.owners()) {
            owners.put(owner.path(), owner);
        }
        Map<String, Map<String, List<OpenLineageSnapshot.SourceColumn>>> byOutput = new LinkedHashMap<>();
        for (FieldMappingExtractor.ColumnMapping mapping : extraction.mappings()) {
            FieldMappingExtractor.Owner owner = owners.get(mapping.ownerPath());
            OpenLineageSnapshot.Dataset output = resolveOutput(outputs, owner);
            if (output == null) {
                continue;
            }
            List<OpenLineageSnapshot.SourceColumn> sources = resolveSources(inputs, owner, mapping);
            if (sources.isEmpty()) {
                continue;
            }
            byOutput.computeIfAbsent(key(output), ignored -> new LinkedHashMap<>())
                    .merge(mapping.outputColumn(), sources, OpenLineageColumnLineageBuilder::merge);
        }
        List<OpenLineageSnapshot.OutputLineage> lineages = new ArrayList<>();
        for (OpenLineageSnapshot.Dataset output : outputs) {
            Map<String, List<OpenLineageSnapshot.SourceColumn>> columns = byOutput.get(key(output));
            if (columns == null || columns.isEmpty()) {
                continue;
            }
            lineages.add(new OpenLineageSnapshot.OutputLineage(output, columns));
        }
        return lineages;
    }

    /**
     * The output dataset a mapping writes to: the only one of the job, or the one the transform
     * names as its result table.
     */
    private static OpenLineageSnapshot.Dataset resolveOutput(
            List<OpenLineageSnapshot.Dataset> outputs, FieldMappingExtractor.Owner owner) {
        if (outputs.size() == 1) {
            return outputs.get(0);
        }
        if (owner == null || StringUtils.isBlank(owner.resultTableName())) {
            return null;
        }
        return matchTable(outputs, owner.resultTableName());
    }

    /**
     * The source columns of one mapping, each bound to the dataset it comes from.
     */
    private static List<OpenLineageSnapshot.SourceColumn> resolveSources(
            List<OpenLineageSnapshot.Dataset> inputs,
            FieldMappingExtractor.Owner owner,
            FieldMappingExtractor.ColumnMapping mapping) {
        List<OpenLineageSnapshot.SourceColumn> sources = new ArrayList<>();
        for (String sourceColumn : mapping.sourceColumns()) {
            OpenLineageSnapshot.Dataset dataset = resolveSource(inputs, owner, mapping, sourceColumn);
            if (dataset == null) {
                continue;
            }
            sources.add(new OpenLineageSnapshot.SourceColumn(dataset, lastSegment(sourceColumn)));
        }
        return sources;
    }

    /** The dataset one source column comes from, or null when it cannot be resolved. */
    private static OpenLineageSnapshot.Dataset resolveSource(
            List<OpenLineageSnapshot.Dataset> inputs,
            FieldMappingExtractor.Owner owner,
            FieldMappingExtractor.ColumnMapping mapping,
            String sourceColumn) {
        if (inputs.size() == 1) {
            return inputs.get(0);
        }
        if (owner == null) {
            // A field mapper names a column only; without a table it is ambiguous.
            return null;
        }
        String table = null;
        if (mapping.qualified()) {
            String alias = aliasOf(sourceColumn);
            if (alias != null) {
                table = owner.aliasToTable().get(alias.toLowerCase(Locale.ROOT));
            }
            if (table == null) {
                // An unqualified column of a projection that reads exactly one table.
                table = onlyTable(owner);
            }
        }
        if (table == null) {
            table = owner.sourceTableName();
        }
        if (table == null) {
            return null;
        }
        return matchTable(inputs, table);
    }

    /** The single table a SQL projection reads, when it reads exactly one. */
    private static String onlyTable(FieldMappingExtractor.Owner owner) {
        Set<String> tables = new LinkedHashSet<>(owner.aliasToTable().values());
        return tables.size() == 1 ? tables.iterator().next() : null;
    }

    /** The table alias of a qualified column, e.g. {@code a} of {@code a.x}. */
    private static String aliasOf(String column) {
        int lastDot = column.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return column.substring(0, lastDot);
    }

    /** The dataset of a table path: exact match first, then the table name alone when it names exactly one dataset. */
    private static OpenLineageSnapshot.Dataset matchTable(
            List<OpenLineageSnapshot.Dataset> datasets, String table) {
        String value = StringUtils.trimToNull(table);
        if (value == null) {
            return null;
        }
        for (OpenLineageSnapshot.Dataset dataset : datasets) {
            if (value.equalsIgnoreCase(dataset.name())) {
                return dataset;
            }
        }
        String last = lastSegment(value);
        OpenLineageSnapshot.Dataset found = null;
        for (OpenLineageSnapshot.Dataset dataset : datasets) {
            if (!last.equalsIgnoreCase(lastSegment(dataset.name()))) {
                continue;
            }
            if (found != null) {
                // Several datasets share the table name, e.g. the same table in two schemas.
                // The mapping does not say which one it means, so the column stays unresolved
                // instead of being attached to one of them by chance.
                return null;
            }
            found = dataset;
        }
        return found;
    }

    private static List<OpenLineageSnapshot.SourceColumn> merge(
            List<OpenLineageSnapshot.SourceColumn> left,
            List<OpenLineageSnapshot.SourceColumn> right) {
        List<OpenLineageSnapshot.SourceColumn> merged = new ArrayList<>(left);
        for (OpenLineageSnapshot.SourceColumn candidate : right) {
            if (!merged.contains(candidate)) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private static String lastSegment(String identifier) {
        int lastDot = identifier.lastIndexOf('.');
        return lastDot >= 0 ? identifier.substring(lastDot + 1) : identifier;
    }

    private static String key(OpenLineageSnapshot.Dataset dataset) {
        return dataset.namespace() + "/" + dataset.name();
    }
}
