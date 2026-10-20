package org.apache.seatunnel.web.api.metadata;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the column field mappings from the runtime config of an accepted SeaTunnel job.
 *
 * <p>
 * This is a pure parser over the job's own configuration: it performs no OpenMetadata I/O and
 * shares nothing with the OpenMetadata lineage request JSON. The OpenMetadata lineage writer and
 * the OpenLineage Kafka bridge both consume it as "job config → column mappings" input while
 * building their own, unrelated output formats.
 * </p>
 *
 * <p>
 * Two levels of detail are offered: {@link #extract(Config)} returns the plain mapping the
 * OpenMetadata channel needs (output column → source column names), while
 * {@link #extractDetailed(Config)} additionally reports which transform block declared each
 * mapping and which tables that block names. The OpenLineage channel needs the second level to
 * attribute a column to the table it comes from.
 * </p>
 */
public final class FieldMappingExtractor {

    private FieldMappingExtractor() {}

    /**
     * One column mapping: the output column and the source columns it reads, plus the block that
     * declared it.
     *
     * @param outputColumn the column the mapping writes
     * @param sourceColumns the columns the mapping reads; they keep their table qualifier when
     *     {@code qualified} is set (SQL projections) and are plain names otherwise (field mappers)
     * @param ownerPath path of the transform block that declared the mapping
     * @param qualified whether the source columns keep their table qualifier
     */
    public record ColumnMapping(
            String outputColumn,
            Set<String> sourceColumns,
            String ownerPath,
            boolean qualified) {
    }

    /**
     * What one transform block says about its tables.
     *
     * @param path path of the block inside the job configuration
     * @param aliasToTable table of every alias of a SQL projection, keyed by the lower case alias
     * @param sourceTableName the source table a transform names explicitly, when it names one
     * @param resultTableName the result table a transform names explicitly, when it names one
     */
    public record Owner(
            String path,
            Map<String, String> aliasToTable,
            String sourceTableName,
            String resultTableName) {
    }

    /**
     * The detailed extraction: the column mappings and the blocks that declared them.
     */
    public record Result(List<ColumnMapping> mappings, List<Owner> owners) {
    }

    /**
     * Reads every FieldMapper target-to-source mapping plus the projections of generated Sql
     * transforms. Target fields that no connector maps are absent, source fields unrecognized by
     * the parser are dropped rather than guessed.
     *
     * @return mapping from target field name to the set of source field names it reads from
     */
    public static Map<String, Set<String>> extract(Config runtimeConfig) {
        Map<String, Set<String>> mappings = new TreeMap<>();
        for (ColumnMapping mapping : extractDetailed(runtimeConfig).mappings()) {
            Set<String> columns =
                    mappings.computeIfAbsent(mapping.outputColumn(), ignored -> new TreeSet<>());
            for (String column : mapping.sourceColumns()) {
                columns.add(mapping.qualified() ? lastSegment(column) : column);
            }
        }
        return mappings;
    }

    /**
     * The detailed extraction: the same mappings as {@link #extract(Config)}, plus the transform
     * block that declared each of them and the tables that block names.
     */
    public static Result extractDetailed(Config runtimeConfig) {
        if (runtimeConfig == null) {
            return new Result(List.of(), List.of());
        }
        Collector collector = new Collector();
        collectFieldMappings(runtimeConfig.root(), "", collector);
        collectSqlTransforms(runtimeConfig, collector);
        return new Result(collector.mappings, collector.owners());
    }

    /** Collects the mappings and the transform blocks that declared them. */
    private static final class Collector {

        private final List<ColumnMapping> mappings = new ArrayList<>();
        private final Map<String, Owner> owners = new LinkedHashMap<>();

        private void addMapping(String outputColumn, Set<String> sourceColumns, String ownerPath) {
            mappings.add(new ColumnMapping(outputColumn, sourceColumns, ownerPath, false));
        }

        private void addSqlMapping(
                String outputColumn, Set<String> sourceColumns, String ownerPath) {
            mappings.add(new ColumnMapping(outputColumn, sourceColumns, ownerPath, true));
        }

        private void addOwner(Owner owner) {
            owners.putIfAbsent(owner.path(), owner);
        }

        private List<Owner> owners() {
            return new ArrayList<>(owners.values());
        }
    }

    private static void collectFieldMappings(
            ConfigValue value, String path, Collector collector) {
        if (value instanceof ConfigObject object) {
            ConfigValue mapperValue = object.get("field_mapper");
            if (mapperValue instanceof ConfigObject mapper) {
                for (Map.Entry<String, ConfigValue> entry : mapper.entrySet()) {
                    if (entry.getValue().valueType() != ConfigValueType.STRING) {
                        continue;
                    }
                    String sourceField = normalizeIdentifier(entry.getKey());
                    String targetField = normalizeIdentifier(entry.getValue().unwrapped().toString());
                    if (StringUtils.isAnyBlank(sourceField, targetField)) {
                        continue;
                    }
                    collector.addMapping(targetField, Set.of(sourceField), path);
                }
            }
            for (Map.Entry<String, ConfigValue> entry : object.entrySet()) {
                collectFieldMappings(entry.getValue(), childPath(path, entry.getKey()), collector);
            }
        } else if (value instanceof ConfigList list) {
            int index = 0;
            for (ConfigValue child : list) {
                collectFieldMappings(child, path + "[" + index + "]", collector);
                index++;
            }
        }
    }

    /**
     * A many-to-one mapping is submitted as a {@code Sql} transform, so its projection is the
     * mapping. Only the transform section is read: a source connector may carry a query of its own
     * that says nothing about the target table.
     */
    private static void collectSqlTransforms(Config runtimeConfig, Collector collector) {
        if (!runtimeConfig.hasPath("transform")) {
            return;
        }
        collectSqlQueries(runtimeConfig.getValue("transform"), "transform", collector);
    }

    private static void collectSqlQueries(
            ConfigValue value, String path, Collector collector) {
        if (value instanceof ConfigObject object) {
            ConfigValue queryValue = object.get("query");
            if (queryValue != null && queryValue.valueType() == ConfigValueType.STRING) {
                String query = queryValue.unwrapped().toString();
                collector.addOwner(new Owner(
                        path,
                        parseFromTables(query),
                        string(object, "source_table_name"),
                        string(object, "result_table_name")));
                collectProjectionMappings(query, collector, path);
            }
            for (Map.Entry<String, ConfigValue> entry : object.entrySet()) {
                collectSqlQueries(entry.getValue(), childPath(path, entry.getKey()), collector);
            }
        } else if (value instanceof ConfigList list) {
            int index = 0;
            for (ConfigValue child : list) {
                collectSqlQueries(child, path + "[" + index + "]", collector);
                index++;
            }
        }
    }

    /**
     * Reads the {@code SELECT} list of a generated projection: every item maps its target alias to
     * the source columns the expression reads. Anything this cannot read exactly (a star, a join,
     * an item without an alias) is left out instead of guessed.
     */
    private static void collectProjectionMappings(
            String query, Collector collector, String ownerPath) {
        if (StringUtils.isBlank(query)) {
            return;
        }
        int select = indexOfKeyword(query, "select", 0);
        if (select < 0) {
            return;
        }
        int from = indexOfKeyword(query, "from", select + "select".length());
        if (from < 0) {
            return;
        }
        String projection = query.substring(select + "select".length(), from);
        if (projection.contains("*")) {
            return;
        }
        for (String item : splitTopLevel(projection, ',')) {
            addProjectionItem(item, collector, ownerPath);
        }
    }

    private static void addProjectionItem(
            String item, Collector collector, String ownerPath) {
        String trimmed = StringUtils.trimToNull(item);
        if (trimmed == null) {
            return;
        }
        String alias = null;
        String expression = trimmed;
        int as = indexOfKeyword(trimmed, "as", 0);
        if (as >= 0) {
            alias = unquoteIdentifier(trimmed.substring(as + "as".length()));
            expression = trimmed.substring(0, as);
        } else if (isPlainIdentifier(trimmed)) {
            alias = unquoteIdentifier(trimmed);
        }
        if (StringUtils.isBlank(alias)) {
            return;
        }
        Set<String> sourceColumns = identifiersIn(expression, true);
        if (sourceColumns.isEmpty()) {
            return;
        }
        collector.addSqlMapping(alias, sourceColumns, ownerPath);
    }

    /**
     * The tables of a {@code FROM} clause, keyed by their alias (lower case) and by their own
     * name. Only plain table references are read; a subquery is skipped.
     */
    private static Map<String, String> parseFromTables(String query) {
        Map<String, String> tables = new LinkedHashMap<>();
        int select = indexOfKeyword(query, "select", 0);
        if (select < 0) {
            return tables;
        }
        int from = indexOfKeyword(query, "from", select + "select".length());
        if (from < 0) {
            return tables;
        }
        String clause = query.substring(from + "from".length(), fromClauseEnd(query, from));
        List<String> tokens = tokenize(clause);
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (isJoinKeyword(token)) {
                continue;
            }
            if (isClauseKeyword(token)) {
                break;
            }
            if (!isIdentifierLike(token)) {
                continue;
            }
            String table = token;
            String alias = null;
            if (index + 1 < tokens.size()
                    && isIdentifierLike(tokens.get(index + 1))
                    && !isClauseKeyword(tokens.get(index + 1))
                    && !isJoinKeyword(tokens.get(index + 1))) {
                alias = tokens.get(index + 1);
                index++;
            }
            tables.put(table.toLowerCase(Locale.ROOT), table);
            if (alias != null) {
                tables.put(alias.toLowerCase(Locale.ROOT), table);
            }
        }
        return tables;
    }

    /** The end of the {@code FROM} clause: the first clause keyword that follows it. */
    private static int fromClauseEnd(String query, int from) {
        int end = query.length();
        for (String keyword : List.of("on", "where", "group", "order", "having", "limit", "union",
                "intersect", "except", "window", "qualify", "using")) {
            int index = indexOfKeyword(query, keyword, from);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return end;
    }

    private static List<String> tokenize(String clause) {
        List<String> tokens = new ArrayList<>();
        for (String raw : clause.split("\\s+")) {
            String token = raw.replace(",", "").trim();
            if (token.isEmpty() || token.contains("(") || token.contains(")")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static boolean isJoinKeyword(String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "join":
            case "inner":
            case "left":
            case "right":
            case "full":
            case "cross":
            case "outer":
                return true;
            default:
                return false;
        }
    }

    private static boolean isClauseKeyword(String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "on":
            case "where":
            case "group":
            case "order":
            case "having":
            case "limit":
            case "union":
            case "intersect":
            case "except":
            case "window":
            case "qualify":
            case "using":
            case "as":
                return true;
            default:
                return false;
        }
    }

    private static boolean isIdentifierLike(String token) {
        return token.matches("[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*");
    }

    /** Identifiers an expression reads: string literals are dropped, quoted names are kept. */
    private static Set<String> identifiersIn(String expression, boolean qualified) {
        Set<String> identifiers = new TreeSet<>();
        StringBuilder text = new StringBuilder(expression.length());
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (current == '\'') {
                index++;
                while (index < expression.length() && expression.charAt(index) != '\'') {
                    index++;
                }
                index++;
                text.append(' ');
                continue;
            }
            if (current == '`' || current == '"') {
                int end = expression.indexOf(current, index + 1);
                if (end < 0) {
                    break;
                }
                text.append(' ').append(expression, index + 1, end).append(' ');
                index = end + 1;
                continue;
            }
            text.append(current);
            index++;
        }
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            // A name directly followed by "(" is a function, not a column.
            if (nextNonSpace(text, matcher.end()) == '(') {
                continue;
            }
            String matched = matcher.group().replaceAll("\\s*\\.\\s*", ".");
            int lastDot = matched.lastIndexOf('.');
            String column = (lastDot >= 0 ? matched.substring(lastDot + 1) : matched).trim();
            if (StringUtils.isBlank(column)
                    || SQL_RESERVED_WORDS.contains(column.toUpperCase(Locale.ROOT))) {
                continue;
            }
            identifiers.add(qualified ? matched : column);
        }
        return identifiers;
    }

    private static char nextNonSpace(CharSequence text, int fromIndex) {
        for (int index = fromIndex; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return text.charAt(index);
            }
        }
        return 0;
    }

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_$]*)*");

    private static final Set<String> SQL_RESERVED_WORDS = Set.of(
            "AND",
            "AS",
            "BETWEEN",
            "CASE",
            "CAST",
            "DISTINCT",
            "ELSE",
            "END",
            "FALSE",
            "FROM",
            "IN",
            "IS",
            "LIKE",
            "NOT",
            "NULL",
            "OR",
            "SELECT",
            "THEN",
            "TRUE",
            "WHEN",
            "WHERE"
    );

    private static int indexOfKeyword(String text, String keyword, int fromIndex) {
        int depth = 0;
        char quote = 0;
        for (int i = fromIndex; i + keyword.length() <= text.length(); i++) {
            char current = text.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (current == '(') {
                depth++;
                continue;
            }
            if (current == ')') {
                depth--;
                continue;
            }
            if (depth != 0 || Character.toLowerCase(current) != keyword.charAt(0)) {
                continue;
            }
            if (text.regionMatches(true, i, keyword, 0, keyword.length())
                    && !isIdentifierChar(text, i - 1)
                    && !isIdentifierChar(text, i + keyword.length())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isIdentifierChar(String text, int index) {
        return index >= 0
                && index < text.length()
                && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_');
    }

    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static boolean isPlainIdentifier(String text) {
        String unquoted = text;
        if (text.length() >= 2
                && (text.startsWith("`") || text.startsWith("\"") || text.startsWith("'"))
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            unquoted = text.substring(1, text.length() - 1);
        }
        return unquoted.matches("[A-Za-z_][A-Za-z0-9_$]*");
    }

    private static String unquoteIdentifier(String text) {
        String trimmed = StringUtils.trimToNull(text);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() >= 2
                && (trimmed.startsWith("`") || trimmed.startsWith("\""))
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return normalizeIdentifier(trimmed.substring(1, trimmed.length() - 1));
        }
        return normalizeIdentifier(trimmed);
    }

    private static String normalizeIdentifier(String value) {
        return StringUtils.trimToNull(value) == null
                ? null
                : value.trim().replace("`", "").replace("\"", "").replace("'", "");
    }

    private static String lastSegment(String identifier) {
        int lastDot = identifier.lastIndexOf('.');
        return lastDot >= 0 ? identifier.substring(lastDot + 1) : identifier;
    }

    private static String childPath(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String string(ConfigObject object, String key) {
        ConfigValue value = object.get(key);
        if (value == null || value.valueType() != ConfigValueType.STRING) {
            return null;
        }
        return StringUtils.trimToNull(value.unwrapped().toString());
    }
}
