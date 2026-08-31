package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractValidator;
import org.apache.seatunnel.web.api.lake.contract.TargetDistribution;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.contract.TargetType;
import org.apache.seatunnel.web.common.enums.LakeTableModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the small, structural subset of a Doris 4.1.2 SHOW CREATE result that
 * is managed by the Web control plane.
 *
 * <p>The raw DDL is deliberately not retained or compared.  Doris emits
 * server defaults (and prints Contract STRING as {@code text}), so this class
 * turns only columns, model/key, auto partition and distribution into the
 * canonical TargetContract representation.</p>
 */
public final class DorisContractReader {

    private static final Pattern MODEL_AND_KEY = Pattern.compile(
            "(?is)\\b(UNIQUE|DUPLICATE)\\s+KEY\\s*\\(");
    private static final Pattern AUTO_PARTITION = Pattern.compile(
            "(?is)\\bAUTO\\s+PARTITION\\s+BY\\s+RANGE\\s*\\(\\s*DATE_TRUNC\\s*\\(");
    private static final Pattern DISTRIBUTION = Pattern.compile(
            "(?is)\\bDISTRIBUTED\\s+BY\\s+(RANDOM|HASH)");
    private static final Pattern BUCKETS = Pattern.compile(
            "(?is)\\bBUCKETS\\s+(AUTO|[0-9]+)");

    /** Parses one complete SHOW CREATE TABLE statement. */
    public TargetContract read(String showCreateSql) {
        String sql = requireSql(showCreateSql);
        int columnsOpen = findFirstOpenParenthesis(sql, 0);
        if (columnsOpen < 0) {
            throw malformed("CREATE TABLE column list is missing");
        }
        int columnsClose = matchingParenthesis(sql, columnsOpen);
        List<ParsedColumn> parsedColumns = parseColumns(sql.substring(columnsOpen + 1, columnsClose));
        if (parsedColumns.isEmpty()) {
            throw malformed("CREATE TABLE column list is empty");
        }

        KeyClause keyClause = parseKeyClause(sql, columnsClose + 1);
        Map<String, ParsedColumn> columnsByName = new LinkedHashMap<>();
        List<TargetColumn> columns = new ArrayList<>(parsedColumns.size());
        for (int index = 0; index < parsedColumns.size(); index++) {
            ParsedColumn parsed = parsedColumns.get(index);
            String targetName = DorisIdentifier.normalize(parsed.name());
            if (columnsByName.put(targetName, parsed) != null) {
                throw malformed("Duplicate Doris column name");
            }
            columns.add(new TargetColumn(parsed.name(), index + 1, targetName,
                    TargetType.parseDorisType(parsed.type()), parsed.nullable(),
                    keyClause.columns().contains(targetName), index + 1));
        }
        if (keyClause.columns().isEmpty()) {
            throw malformed("Doris key clause is missing");
        }
        for (String key : keyClause.columns()) {
            if (!columnsByName.containsKey(key)) {
                throw malformed("Doris key column is not declared");
            }
        }

        TargetPartition partition = parsePartition(sql, columnsByName);
        TargetDistribution distribution = parseDistribution(sql);
        TargetContract contract = new TargetContract(TargetContract.CURRENT_VERSION,
                keyClause.model(), columns, keyClause.columns(), partition, distribution);
        return TargetContractValidator.validateAndNormalize(contract);
    }

    /** Compatibility alias used by metadata callers. */
    public TargetContract readContract(String showCreateSql) {
        return read(showCreateSql);
    }

    /**
     * Reads only explicit PROPERTIES assignments.  Unknown Doris defaults are
     * returned as-is for diagnostics and are never fed to the DDL whitelist.
     */
    public Map<String, String> readProperties(String showCreateSql) {
        String sql = requireSql(showCreateSql);
        int properties = indexOfWord(sql, "PROPERTIES", 0);
        if (properties < 0) {
            return Map.of();
        }
        int open = findFirstOpenParenthesis(sql, properties + "PROPERTIES".length());
        if (open < 0) {
            throw malformed("Doris properties clause is malformed");
        }
        int close = matchingParenthesis(sql, open);
        return parseAssignments(sql.substring(open + 1, close));
    }

    /** Compatibility alias for callers that distinguish table metadata. */
    public Map<String, String> readTableProperties(String showCreateSql) {
        return readProperties(showCreateSql);
    }

    private static List<ParsedColumn> parseColumns(String body) {
        List<ParsedColumn> columns = new ArrayList<>();
        for (String definition : splitTopLevel(body, ',')) {
            String value = definition.trim();
            if (value.isEmpty() || value.startsWith("--") || value.startsWith("#")) {
                continue;
            }
            IdentifierToken identifier = readIdentifier(value, 0);
            int offset = skipWhitespace(value, identifier.end());
            Token type = readType(value, offset);
            if (type.value().isBlank()) {
                throw malformed("Doris column type is missing");
            }
            // SHOW CREATE normally includes NULL/NOT NULL. If it omits the
            // clause, Doris treats the column as nullable.
            boolean nullable = !containsNotNull(value.substring(type.end()));
            columns.add(new ParsedColumn(identifier.value(), type.value(), nullable));
        }
        return columns;
    }

    private static KeyClause parseKeyClause(String sql, int start) {
        Matcher matcher = MODEL_AND_KEY.matcher(sql);
        if (!matcher.find(start)) {
            return new KeyClause(null, List.of());
        }
        int open = sql.indexOf('(', matcher.start());
        int close = matchingParenthesis(sql, open);
        List<String> keys = normalizeIdentifiers(splitTopLevel(sql.substring(open + 1, close), ','));
        if (keys.isEmpty()) {
            throw malformed("Doris key clause is empty");
        }
        LakeTableModel model = "UNIQUE".equalsIgnoreCase(matcher.group(1))
                ? LakeTableModel.UNIQUE : LakeTableModel.DUPLICATE;
        return new KeyClause(model, keys);
    }

    private static TargetPartition parsePartition(String sql, Map<String, ParsedColumn> columns) {
        Matcher matcher = AUTO_PARTITION.matcher(sql);
        if (!matcher.find()) {
            return TargetPartition.disabled();
        }
        int dateTrunc = indexOfWord(sql, "DATE_TRUNC", matcher.start());
        if (dateTrunc < 0) {
            throw malformed("Doris auto partition expression is malformed");
        }
        int dateTruncOpen = findFirstOpenParenthesis(sql, dateTrunc + "DATE_TRUNC".length());
        if (dateTruncOpen < 0) {
            throw malformed("Doris auto partition expression is malformed");
        }
        int dateTruncClose = matchingParenthesis(sql, dateTruncOpen);
        List<String> arguments = splitTopLevel(sql.substring(dateTruncOpen + 1, dateTruncClose), ',');
        if (arguments.size() != 2) {
            throw malformed("Doris auto partition expression is malformed");
        }
        String column = normalizeIdentifier(arguments.get(0));
        String granularity = unquoteSql(arguments.get(1)).trim().toUpperCase(Locale.ROOT);
        if (!columns.containsKey(column)) {
            throw malformed("Doris partition column is not declared");
        }
        // Validate granularity and target type through the common contract
        // validator.  Keeping this check here gives a deterministic reader
        // error before the contract is assembled.
        return TargetPartition.autoRange(column, granularity);
    }

    private static TargetDistribution parseDistribution(String sql) {
        Matcher matcher = DISTRIBUTION.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        String type = matcher.group(1).toUpperCase(Locale.ROOT);
        List<String> columns = List.of();
        int cursor = matcher.end();
        if (TargetDistribution.HASH.equals(type)) {
            int open = findFirstOpenParenthesis(sql, cursor);
            if (open < 0) {
                throw malformed("Doris hash distribution columns are missing");
            }
            int close = matchingParenthesis(sql, open);
            columns = normalizeIdentifiers(splitTopLevel(sql.substring(open + 1, close), ','));
        }
        Matcher bucketMatcher = BUCKETS.matcher(sql);
        if (!bucketMatcher.find(cursor)) {
            throw malformed("Doris bucket clause is missing");
        }
        return new TargetDistribution(type, columns, bucketMatcher.group(1).toUpperCase(Locale.ROOT));
    }

    private static Map<String, String> parseAssignments(String body) {
        if (body.isBlank()) {
            return Map.of();
        }
        TreeMap<String, String> values = new TreeMap<>();
        for (String assignment : splitTopLevel(body, ',')) {
            List<String> pair = splitTopLevel(assignment, '=');
            if (pair.size() != 2) {
                throw malformed("Doris property assignment is malformed");
            }
            String key = unquoteSql(pair.get(0)).trim();
            if (key.isBlank()) {
                throw malformed("Doris property key is blank");
            }
            if (values.put(key, unquoteSql(pair.get(1))) != null) {
                throw malformed("Duplicate Doris property");
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static Token readType(String value, int start) {
        int index = start;
        int parentheses = 0;
        int angleBrackets = 0;
        boolean backtick = false;
        while (index < value.length()) {
            char character = value.charAt(index);
            if (character == '`') {
                backtick = !backtick;
            } else if (!backtick) {
                if (character == '(') {
                    parentheses++;
                } else if (character == ')') {
                    parentheses--;
                } else if (character == '<') {
                    angleBrackets++;
                } else if (character == '>') {
                    angleBrackets--;
                } else if (Character.isWhitespace(character) && parentheses == 0 && angleBrackets == 0) {
                    break;
                }
            }
            index++;
        }
        return new Token(value.substring(start, index), index);
    }

    private static IdentifierToken readIdentifier(String value, int start) {
        int index = skipWhitespace(value, start);
        if (index >= value.length()) {
            throw malformed("Doris column name is missing");
        }
        if (value.charAt(index) == '`') {
            StringBuilder name = new StringBuilder();
            int cursor = index + 1;
            while (cursor < value.length()) {
                char character = value.charAt(cursor++);
                if (character == '`') {
                    if (cursor < value.length() && value.charAt(cursor) == '`') {
                        name.append('`');
                        cursor++;
                    } else {
                        return new IdentifierToken(name.toString(), cursor);
                    }
                } else {
                    name.append(character);
                }
            }
            throw malformed("Doris quoted identifier is unterminated");
        }
        int end = index;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
            end++;
        }
        return new IdentifierToken(value.substring(index, end), end);
    }

    private static List<String> normalizeIdentifiers(List<String> raw) {
        List<String> result = new ArrayList<>(raw.size());
        for (String item : raw) {
            result.add(normalizeIdentifier(item));
        }
        return List.copyOf(result);
    }

    private static String normalizeIdentifier(String value) {
        String token = value.trim();
        if (token.startsWith("`") && token.endsWith("`") && token.length() >= 2) {
            token = token.substring(1, token.length() - 1).replace("``", "`");
        }
        return DorisIdentifier.normalize(token);
    }

    private static String unquoteSql(String value) {
        String token = value.trim();
        if (token.length() >= 2) {
            char first = token.charAt(0);
            char last = token.charAt(token.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                String body = token.substring(1, token.length() - 1);
                String escaped = first == '\'' ? "''" : "\"\"";
                return body.replace(escaped, String.valueOf(first));
            }
        }
        return token;
    }

    private static List<String> splitTopLevel(String value, char separator) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int parentheses = 0;
        int angleBrackets = 0;
        char quote = 0;
        boolean backslash = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (backslash) {
                    backslash = false;
                } else if (character == '\\') {
                    backslash = true;
                } else if (character == quote) {
                    if (index + 1 < value.length() && value.charAt(index + 1) == quote) {
                        index++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (character == '\'' || character == '"' || character == '`') {
                quote = character;
            } else if (character == '(') {
                parentheses++;
            } else if (character == ')') {
                parentheses--;
            } else if (character == '<') {
                angleBrackets++;
            } else if (character == '>') {
                angleBrackets--;
            } else if (character == separator && parentheses == 0 && angleBrackets == 0) {
                result.add(value.substring(start, index).trim());
                start = index + 1;
            }
        }
        if (quote != 0 || parentheses != 0 || angleBrackets != 0) {
            throw malformed("Doris SQL clause is unbalanced");
        }
        result.add(value.substring(start).trim());
        return result;
    }

    private static int findFirstOpenParenthesis(String sql, int start) {
        char quote = 0;
        boolean backslash = false;
        for (int index = Math.max(0, start); index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (quote != 0) {
                if (backslash) {
                    backslash = false;
                } else if (character == '\\') {
                    backslash = true;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"' || character == '`') {
                quote = character;
            } else if (character == '(') {
                return index;
            }
        }
        return -1;
    }

    private static int matchingParenthesis(String sql, int open) {
        if (open < 0 || open >= sql.length() || sql.charAt(open) != '(') {
            throw malformed("Doris SQL parenthesis is missing");
        }
        int depth = 0;
        char quote = 0;
        boolean backslash = false;
        for (int index = open; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (quote != 0) {
                if (backslash) {
                    backslash = false;
                } else if (character == '\\') {
                    backslash = true;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"' || character == '`') {
                quote = character;
            } else if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return index;
            }
        }
        throw malformed("Doris SQL parenthesis is unbalanced");
    }

    private static int indexOfWord(String value, String word, int start) {
        String target = word.toUpperCase(Locale.ROOT);
        char quote = 0;
        boolean backslash = false;
        for (int offset = Math.max(0, start); offset < value.length(); offset++) {
            char character = value.charAt(offset);
            if (quote != 0) {
                if (backslash) {
                    backslash = false;
                } else if (character == '\\') {
                    backslash = true;
                } else if (character == quote) {
                    if (offset + 1 < value.length() && value.charAt(offset + 1) == quote) {
                        offset++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (character == '\'' || character == '"' || character == '`') {
                quote = character;
                continue;
            }
            if (offset + target.length() <= value.length()
                    && value.regionMatches(true, offset, target, 0, target.length())) {
                boolean left = offset == 0 || !Character.isJavaIdentifierPart(value.charAt(offset - 1));
                int end = offset + target.length();
                boolean right = end >= value.length() || !Character.isJavaIdentifierPart(value.charAt(end));
                if (left && right) {
                    return offset;
                }
            }
        }
        return -1;
    }

    private static boolean containsNotNull(String value) {
        return value.trim().toUpperCase(Locale.ROOT).matches("(?s).*\\bNOT\\s+NULL\\b.*");
    }

    private static int skipWhitespace(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String requireSql(String value) {
        if (value == null || value.isBlank()) {
            throw malformed("Doris SHOW CREATE result is blank");
        }
        return value;
    }

    private static IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException(message);
    }

    private record ParsedColumn(String name, String type, boolean nullable) {
    }

    private record IdentifierToken(String value, int end) {
    }

    private record Token(String value, int end) {
    }

    private record KeyClause(LakeTableModel model, List<String> columns) {
    }
}
