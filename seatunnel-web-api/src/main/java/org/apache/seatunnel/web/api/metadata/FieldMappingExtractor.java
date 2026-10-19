package org.apache.seatunnel.web.api.metadata;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
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
 */
public final class FieldMappingExtractor {

    private FieldMappingExtractor() {}

    /**
     * Reads every FieldMapper target-to-source mapping plus the projections of generated Sql
     * transforms. Target fields that no connector maps are absent, source fields unrecognized by
     * the parser are dropped rather than guessed.
     *
     * @return mapping from target field name to the set of source field names it reads from
     */
    public static Map<String, Set<String>> extract(Config runtimeConfig) {
        if (runtimeConfig == null) {
            return Map.of();
        }
        Map<String, Set<String>> mappings = new TreeMap<>();
        collectFieldMappings(runtimeConfig.root(), mappings);
        collectSqlTransformMappings(runtimeConfig, mappings);
        return mappings;
    }

    private static void collectFieldMappings(ConfigValue value, Map<String, Set<String>> mappings) {
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
                    mappings.computeIfAbsent(targetField, ignored -> new TreeSet<>()).add(sourceField);
                }
            }
            object.values().forEach(child -> collectFieldMappings(child, mappings));
        } else if (value instanceof ConfigList list) {
            list.forEach(child -> collectFieldMappings(child, mappings));
        }
    }

    /**
     * A many-to-one mapping is submitted as a {@code Sql} transform, so its projection is the
     * mapping. Only the transform section is read: a source connector may carry a query of its own
     * that says nothing about the target table.
     */
    private static void collectSqlTransformMappings(
            Config runtimeConfig, Map<String, Set<String>> mappings) {
        if (!runtimeConfig.hasPath("transform")) {
            return;
        }
        collectSqlQueries(runtimeConfig.getValue("transform"), mappings);
    }

    private static void collectSqlQueries(ConfigValue value, Map<String, Set<String>> mappings) {
        if (value instanceof ConfigObject object) {
            ConfigValue queryValue = object.get("query");
            if (queryValue != null && queryValue.valueType() == ConfigValueType.STRING) {
                collectProjectionMappings(queryValue.unwrapped().toString(), mappings);
            }
            object.values().forEach(child -> collectSqlQueries(child, mappings));
        } else if (value instanceof ConfigList list) {
            list.forEach(child -> collectSqlQueries(child, mappings));
        }
    }

    /**
     * Reads the {@code SELECT} list of a generated projection: every item maps its target alias to
     * the source columns the expression reads. Anything this cannot read exactly (a star, a join,
     * an item without an alias) is left out instead of guessed.
     */
    private static void collectProjectionMappings(String query, Map<String, Set<String>> mappings) {
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
            addProjectionItem(item, mappings);
        }
    }

    private static void addProjectionItem(String item, Map<String, Set<String>> mappings) {
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
        Set<String> sourceFields = new TreeSet<>();
        for (String identifier : identifiersIn(expression)) {
            sourceFields.add(identifier);
        }
        if (sourceFields.isEmpty()) {
            return;
        }
        mappings.computeIfAbsent(alias, ignored -> new TreeSet<>()).addAll(sourceFields);
    }

    /** Identifiers an expression reads: string literals are dropped, quoted names are kept. */
    private static Set<String> identifiersIn(String expression) {
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
            String matched = matcher.group();
            int lastDot = matched.lastIndexOf('.');
            String column = (lastDot >= 0 ? matched.substring(lastDot + 1) : matched).trim();
            if (StringUtils.isBlank(column)
                    || SQL_RESERVED_WORDS.contains(column.toUpperCase(Locale.ROOT))) {
                continue;
            }
            identifiers.add(column);
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
}
