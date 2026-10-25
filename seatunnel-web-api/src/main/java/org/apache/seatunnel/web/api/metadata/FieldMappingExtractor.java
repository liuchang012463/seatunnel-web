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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the column field mappings from the runtime config of an accepted SeaTunnel job.
 *
 * <p>
 * This is a pure parser over the job's own configuration: it performs no OpenMetadata I/O and
 * shares nothing with any lineage request JSON. The OpenLineage Kafka bridge consumes it as
 * "job config → column mappings" input while building its own event format.
 * </p>
 *
 * <p>
 * {@link #extractDetailed(Config)} reports which transform block declared each mapping and which
 * tables that block names. The OpenLineage channel needs this detail to attribute a column to the
 * table it comes from.
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
     * The detailed extraction: every FieldMapper target-to-source mapping plus the projections of
     * generated Sql transforms. Target fields that no connector maps are absent, source fields
     * unrecognized by the parser are dropped rather than guessed.
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
                if (!object.containsKey("query")) {
                    // A field mapper names the table it reads and the table it writes through the
                    // plugin aliases. Without an owner the columns of a job with more than one
                    // source or sink cannot be attributed to a table at all.
                    collector.addOwner(new Owner(
                            path,
                            Map.of(),
                            string(object, "plugin_input"),
                            string(object, "plugin_output")));
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
                        firstNonBlank(string(object, "source_table_name"), string(object, "plugin_input")),
                        firstNonBlank(string(object, "result_table_name"), string(object, "plugin_output"))));
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
     * name. Only plain table references are read; a subquery is skipped. The parser walks table
     * positions instead of stopping at the first join condition, so every joined table and its
     * {@code AS} alias is captured.
     */
    private static Map<String, String> parseFromTables(String query) {
        Map<String, String> tables = new LinkedHashMap<>();
        if (StringUtils.isBlank(query)) {
            return tables;
        }
        int select = indexOfKeyword(query, "select", 0);
        if (select < 0) {
            return tables;
        }
        int from = indexOfKeyword(query, "from", select + "select".length());
        if (from < 0) {
            return tables;
        }
        List<String> tokens = tokenize(query.substring(from + "from".length()));
        boolean expectTable = true;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            String lower = token.toLowerCase(Locale.ROOT);
            if (",".equals(token)) {
                expectTable = true;
                continue;
            }
            if (isFromTerminator(lower)) {
                break;
            }
            if (isJoinKeyword(lower)) {
                expectTable = true;
                continue;
            }
            if (isJoinConditionKeyword(lower)) {
                expectTable = false;
                continue;
            }
            if (!expectTable) {
                continue;
            }
            String table = normalizeTableToken(token);
            if (table == null) {
                continue;
            }
            String alias = null;
            int next = index + 1;
            if (next < tokens.size() && "as".equalsIgnoreCase(tokens.get(next))) {
                next++;
            }
            if (next < tokens.size()) {
                String candidateToken = tokens.get(next);
                String candidateLower = candidateToken.toLowerCase(Locale.ROOT);
                if (!",".equals(candidateToken)
                        && !isFromTerminator(candidateLower)
                        && !isJoinKeyword(candidateLower)
                        && !isJoinConditionKeyword(candidateLower)) {
                    alias = normalizeTableToken(candidateToken);
                    if (alias != null) {
                        index = next;
                    }
                }
            }
            tables.put(table.toLowerCase(Locale.ROOT), table);
            if (alias != null) {
                tables.put(alias.toLowerCase(Locale.ROOT), table);
            }
            expectTable = false;
        }
        return tables;
    }

    private static List<String> tokenize(String clause) {
        List<String> tokens = new ArrayList<>();
        for (String raw : clause.replace(",", " , ").split("\\s+")) {
            String token = raw.trim();
            if (token.isEmpty() || token.contains("(") || token.contains(")")) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static boolean isJoinKeyword(String token) {
        switch (token) {
            case "join":
            case "inner":
            case "left":
            case "right":
            case "full":
            case "cross":
            case "outer":
            case "natural":
            case "straight_join":
                return true;
            default:
                return false;
        }
    }

    /** A keyword that starts the condition of a join; the next table follows a join keyword. */
    private static boolean isJoinConditionKeyword(String token) {
        switch (token) {
            case "on":
            case "using":
                return true;
            default:
                return false;
        }
    }

    /** A keyword that ends the FROM clause. */
    private static boolean isFromTerminator(String token) {
        switch (token) {
            case "where":
            case "group":
            case "order":
            case "having":
            case "limit":
            case "offset":
            case "fetch":
            case "union":
            case "intersect":
            case "except":
            case "window":
            case "qualify":
                return true;
            default:
                return false;
        }
    }

    /**
     * A table or alias token: a quoted identifier keeps its content verbatim (SeaTunnel generated
     * names may contain hyphens), an unquoted one has to be a plain dotted identifier.
     */
    private static String normalizeTableToken(String token) {
        String trimmed = StringUtils.trimToNull(token);
        if (trimmed == null) {
            return null;
        }
        String value = unquoteToken(trimmed);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        if (!isQuoted(trimmed) && !value.matches("[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*")) {
            return null;
        }
        return SQL_RESERVED_WORDS.contains(value.toUpperCase(Locale.ROOT)) ? null : value;
    }

    private static boolean isQuoted(String token) {
        if (token.length() < 2) {
            return false;
        }
        char first = token.charAt(0);
        return (first == '`' || first == '"') && token.charAt(token.length() - 1) == first;
    }

    private static String unquoteToken(String token) {
        return isQuoted(token) ? token.substring(1, token.length() - 1) : token;
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
        String readable = blankCastTypes(text);
        Matcher matcher = IDENTIFIER.matcher(readable);
        while (matcher.find()) {
            // A name directly followed by "(" is a function, not a column.
            if (nextNonSpace(readable, matcher.end()) == '(') {
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

    /**
     * Blanks the type of a {@code CAST(expression AS type)}: the type is a data type, not a
     * column, and it may consist of several words ({@code double precision},
     * {@code timestamp with time zone}). Without this the lineage claims that the output column
     * reads a column called {@code jsonb} or {@code bytea}.
     */
    private static String blankCastTypes(CharSequence text) {
        StringBuilder result = new StringBuilder(text);
        int index = 0;
        while (index < result.length()) {
            int asIndex = indexOfWord(result, index, "AS");
            if (asIndex < 0) {
                return result.toString();
            }
            int open = enclosingParenthesis(result, asIndex);
            if (open < 0 || !isCastFunction(wordBefore(result, open))) {
                index = asIndex + 2;
                continue;
            }
            int close = matchingParenthesis(result, open);
            if (close < 0) {
                return result.toString();
            }
            for (int i = asIndex + 2; i < close; i++) {
                if (!Character.isWhitespace(result.charAt(i))) {
                    result.setCharAt(i, ' ');
                }
            }
            index = close + 1;
        }
        return result.toString();
    }

    /** The index of the next standalone word, or -1. */
    private static int indexOfWord(CharSequence text, int from, String word) {
        Matcher matcher = STANDALONE_WORD.matcher(text);
        matcher.region(from, text.length());
        while (matcher.find()) {
            if (word.equalsIgnoreCase(matcher.group())) {
                return matcher.start();
            }
        }
        return -1;
    }

    /** The parenthesis that opens the group the index is in, or -1. */
    private static int enclosingParenthesis(CharSequence text, int index) {
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            char current = text.charAt(i);
            if (current == ')') {
                depth++;
            } else if (current == '(') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /** The parenthesis that closes the group the index opens, or -1. */
    private static int matchingParenthesis(CharSequence text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The word that ends right before the index. */
    private static String wordBefore(CharSequence text, int index) {
        int end = index;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && isWordCharacter(text.charAt(start - 1))) {
            start--;
        }
        return text.subSequence(start, end).toString();
    }

    private static boolean isCastFunction(String function) {
        return "CAST".equalsIgnoreCase(function)
                || "CONVERT".equalsIgnoreCase(function)
                || "TRY_CAST".equalsIgnoreCase(function)
                || "SAFE_CAST".equalsIgnoreCase(function);
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '$';
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

    /** A word on its own, used to find the {@code AS} of a cast. */
    private static final Pattern STANDALONE_WORD =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private static final Set<String> SQL_RESERVED_WORDS = Set.of(
            "AND",
            "ARRAY",
            "AS",
            "BETWEEN",
            "BIGINT",
            "BINARY",
            "BLOB",
            "BOOLEAN",
            "BYTE",
            "BYTES",
            "CASE",
            "CAST",
            "CHAR",
            "CHARACTER",
            "CLOB",
            "DATE",
            "DATETIME",
            "DAY",
            "DECIMAL",
            "DISTINCT",
            "DOUBLE",
            "ELSE",
            "END",
            "FALSE",
            "FLOAT",
            "FROM",
            "GEOMETRY",
            "HOUR",
            "IN",
            "INT",
            "INTEGER",
            "INTERVAL",
            "IS",
            "JSON",
            "LIKE",
            "LONG",
            "MAP",
            "MINUTE",
            "MONTH",
            "NCHAR",
            "NOT",
            "NULL",
            "NUMBER",
            "NUMERIC",
            "NVARCHAR",
            "OR",
            "REAL",
            "ROW",
            "SECOND",
            "SELECT",
            "SHORT",
            "SIGNED",
            "SMALLINT",
            "STRING",
            "TEXT",
            "THEN",
            "TIME",
            "TIMESTAMP",
            "TIMESTAMP_LTZ",
            "TIMESTAMP_TZ",
            "TINYINT",
            "TRUE",
            "UNSIGNED",
            "UUID",
            "VARBINARY",
            "VARCHAR",
            "VARCHAR2",
            "WHEN",
            "WHERE",
            "YEAR"
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

    private static String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
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
