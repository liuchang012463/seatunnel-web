package org.apache.seatunnel.web.api.offlineimport;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a PostgreSQL/KingbaseES {@code COPY ... FROM stdin} block.
 *
 * <p>JDBC statements cannot stream COPY data, so the block is parsed and
 * rewritten into batched INSERT statements. Text and CSV formats are
 * supported, including quoted identifiers, custom delimiters, NULL markers
 * and CSV headers. Binary COPY is rejected with an actionable message.</p>
 */
public final class CopyDataConverter {

    private static final Pattern COPY_HEADER = Pattern.compile(
            "(?is)^\\s*COPY\\s+((?:\"(?:[^\"]|\"\")*\"|[^\\s(\".]+)"
                    + "(?:\\.(?:\"(?:[^\"]|\"\")*\"|[^\\s(\".]+))?)\\s*"
                    + "(?:\\(([^)]*)\\))?\\s+FROM\\s+STDIN\\b(.*)$");
    private static final Pattern COPY_OPTION = Pattern.compile(
            "(?i)\\b(DELIMITER|NULL|HEADER|QUOTE|ESCAPE)\\b"
                    + "(?:\\s+[Ee]?'((?:[^']|'')*)'|\\s+\"([^\"]*)\"|\\s+([^\\s,)]+))?");
    private static final int BATCH_ROWS = 200;

    private CopyDataConverter() {
    }

    /** True when the statement starts with a COPY ... FROM stdin header. */
    public static boolean isCopyFromStdin(String statement) {
        if (statement == null) {
            return false;
        }
        int start = firstSignificantIndex(statement);
        if (start >= statement.length() || !"COPY".equalsIgnoreCase(firstWord(statement, start))) {
            return false;
        }
        int headerEnd = findHeaderEnd(statement, start);
        if (headerEnd < 0) {
            return false;
        }
        return COPY_HEADER.matcher(statement.substring(start, headerEnd).trim()).matches();
    }

    /** Executes the COPY block and returns the number of inserted rows. */
    public static int execute(Statement jdbc, String statement) throws SQLException {
        int start = firstSignificantIndex(statement);
        if (start >= statement.length() || !"COPY".equalsIgnoreCase(firstWord(statement, start))) {
            throw new SQLException("COPY 数据块缺少 COPY 头");
        }
        int headerEnd = findHeaderEnd(statement, start);
        if (headerEnd < 0) {
            throw new SQLException("COPY 头缺少结束分号");
        }
        String header = statement.substring(start, headerEnd).trim();
        Matcher parsed = COPY_HEADER.matcher(header);
        if (!parsed.matches()) {
            throw new SQLException("无法解析 COPY 头: " + header);
        }
        String table = parsed.group(1);
        List<String> columns = splitColumns(parsed.group(2));
        String options = parsed.group(3) == null ? "" : parsed.group(3);
        String upperOptions = options.toUpperCase(Locale.ROOT);
        if (upperOptions.contains("BINARY")) {
            throw new SQLException("不支持 COPY BINARY 数据块，请使用 sys_dump --inserts 或 DMP 导入");
        }
        boolean csv = upperOptions.contains("CSV");
        CopyOptions copyOptions = parseOptions(options, csv);

        int dataStart = headerEnd + 1;
        if (dataStart < statement.length() && statement.charAt(dataStart) == '\r') {
            dataStart++;
        }
        if (dataStart < statement.length() && statement.charAt(dataStart) == '\n') {
            dataStart++;
        }
        int dataEnd = findTerminator(statement, dataStart);
        String data = statement.substring(dataStart, dataEnd);

        List<List<String>> rows = csv
                ? parseCsv(data, copyOptions)
                : parseText(data, copyOptions);
        if (copyOptions.header() && !rows.isEmpty()) {
            rows = rows.subList(1, rows.size());
        }
        if (rows.isEmpty()) {
            return 0;
        }

        String prefix = "INSERT INTO " + table
                + (columns.isEmpty() ? "" : " (" + String.join(", ", columns) + ")")
                + " VALUES ";
        int inserted = 0;
        List<String> values = new ArrayList<>(BATCH_ROWS);
        for (List<String> row : rows) {
            if (!columns.isEmpty() && row.size() != columns.size()) {
                throw new SQLException("COPY 数据列数与 COPY 头不一致: 期望 " + columns.size()
                        + " 列，实际 " + row.size() + " 列");
            }
            values.add(toRowLiteral(row));
            if (values.size() == BATCH_ROWS) {
                jdbc.execute(prefix + String.join(", ", values));
                inserted += values.size();
                values.clear();
            }
        }
        if (!values.isEmpty()) {
            jdbc.execute(prefix + String.join(", ", values));
            inserted += values.size();
        }
        return inserted;
    }

    private record CopyOptions(
            char delimiter, String nullMarker, boolean header, char quote, char escape) {
    }

    private static CopyOptions parseOptions(String options, boolean csv) {
        char delimiter = csv ? ',' : '\t';
        // CSV NULL is an unquoted empty field by default; text uses \N.
        String nullMarker = csv ? null : "\\N";
        boolean header = false;
        char quote = '"';
        char escape = '"';
        Matcher matcher = COPY_OPTION.matcher(options);
        while (matcher.find()) {
            String name = matcher.group(1).toUpperCase(Locale.ROOT);
            String rawValue = matcher.group(2) != null
                    ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            String value = rawValue == null ? null : decodeOptionValue(rawValue);
            switch (name) {
                case "DELIMITER" -> {
                    if (value != null && !value.isEmpty()) {
                        delimiter = value.charAt(0);
                    }
                }
                case "NULL" -> nullMarker = value;
                case "HEADER" -> header = parseBoolean(value, true);
                case "QUOTE" -> {
                    if (value != null && !value.isEmpty()) {
                        quote = value.charAt(0);
                    }
                }
                case "ESCAPE" -> {
                    if (value != null && !value.isEmpty()) {
                        escape = value.charAt(0);
                    }
                }
                default -> {
                    // Unknown options keep their default behavior.
                }
            }
        }
        return new CopyOptions(delimiter, nullMarker, header, quote, escape);
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if ("true".startsWith(normalized)
                || "yes".startsWith(normalized)
                || "on".startsWith(normalized)
                || "1".equals(normalized)) {
            return true;
        }
        if ("false".startsWith(normalized)
                || "no".startsWith(normalized)
                || "off".startsWith(normalized)
                || "0".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private static String decodeOptionValue(String value) {
        // COPY options accept E'...' strings; decode the common backslash
        // escapes so DELIMITER E'\t' and DELIMITER E'\x7c' work. The text
        // NULL marker \N must stay literal.
        if ("\\N".equals(value)) {
            return value;
        }
        return decodeTextField(value, null);
    }

    /** Finds the semicolon that ends the COPY header, outside quotes/parentheses. */
    private static int findHeaderEnd(String statement, int from) {
        int depth = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        for (int i = from; i < statement.length(); i++) {
            char c = statement.charAt(i);
            char next = i + 1 < statement.length() ? statement.charAt(i + 1) : '\0';
            if (singleQuote) {
                if (c == '\'') {
                    if (next == '\'') {
                        i++;
                    } else {
                        singleQuote = false;
                    }
                }
                continue;
            }
            if (doubleQuote) {
                if (c == '"') {
                    if (next == '"') {
                        i++;
                    } else {
                        doubleQuote = false;
                    }
                }
                continue;
            }
            if (c == '-' && next == '-') {
                int lineEnd = statement.indexOf('\n', i);
                i = lineEnd < 0 ? statement.length() : lineEnd;
                continue;
            }
            if (c == '/' && next == '*') {
                int close = statement.indexOf("*/", i + 2);
                i = close < 0 ? statement.length() : close + 1;
                continue;
            }
            if (c == '\'') {
                singleQuote = true;
                continue;
            }
            if (c == '"') {
                doubleQuote = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (c == ';' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int firstSignificantIndex(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                int nl = text.indexOf('\n', i);
                if (nl < 0) {
                    return text.length();
                }
                i = nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                if (close < 0) {
                    return text.length();
                }
                i = close + 2;
                continue;
            }
            return i;
        }
        return text.length();
    }

    private static String firstWord(String text, int start) {
        int i = start;
        while (i < text.length()
                && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i++;
        }
        return text.substring(start, i);
    }

    private static List<String> splitColumns(String rawColumns) {
        List<String> columns = new ArrayList<>();
        if (rawColumns == null || rawColumns.isBlank()) {
            return columns;
        }
        for (String column : rawColumns.split(",")) {
            String trimmed = column.trim();
            if (!trimmed.isEmpty()) {
                columns.add(trimmed);
            }
        }
        return columns;
    }

    private static int findTerminator(String statement, int from) throws SQLException {
        int lineStart = from;
        while (lineStart <= statement.length()) {
            int lineEnd = statement.indexOf('\n', lineStart);
            int contentEnd = lineEnd < 0 ? statement.length() : lineEnd;
            String line = statement.substring(lineStart, contentEnd).trim();
            if ("\\.".equals(line)) {
                return lineStart;
            }
            if (lineEnd < 0) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        throw new SQLException("COPY 数据块缺少结束标记 \\.，文件可能被截断");
    }

    private static List<List<String>> parseText(String data, CopyOptions options) {
        List<List<String>> rows = new ArrayList<>();
        String[] lines = data.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index == lines.length - 1 && line.isEmpty()) {
                continue;
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String[] fields = line.split(Pattern.quote(String.valueOf(options.delimiter())), -1);
            List<String> row = new ArrayList<>(fields.length);
            for (String field : fields) {
                row.add(decodeTextField(field, options.nullMarker()));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String decodeTextField(String field, String nullMarker) {
        if (nullMarker != null && field.equals(nullMarker)) {
            return null;
        }
        StringBuilder decoded = new StringBuilder(field.length());
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c != '\\' || i + 1 >= field.length()) {
                decoded.append(c);
                continue;
            }
            char next = field.charAt(++i);
            switch (next) {
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'v' -> decoded.append('\u000B');
                case '\\' -> decoded.append('\\');
                case 'x' -> {
                    int end = Math.min(field.length(), i + 3);
                    String hex = field.substring(i + 1, end);
                    try {
                        decoded.append((char) Integer.parseInt(hex, 16));
                        i = end - 1;
                    } catch (NumberFormatException e) {
                        decoded.append("\\x");
                    }
                }
                default -> {
                    if (next >= '0' && next <= '7') {
                        int end = Math.min(field.length(), i + 3);
                        String octal = field.substring(i, end);
                        try {
                            decoded.append((char) Integer.parseInt(octal, 8));
                            i = end - 1;
                        } catch (NumberFormatException e) {
                            decoded.append(next);
                        }
                    } else {
                        decoded.append(next);
                    }
                }
            }
        }
        return decoded.toString();
    }

    private static List<List<String>> parseCsv(String data, CopyOptions options) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldWasQuoted = false;
        char quote = options.quote();
        char escape = options.escape();
        char delimiter = options.delimiter();
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (quoted) {
                if (c == quote) {
                    if (i + 1 < data.length() && data.charAt(i + 1) == quote) {
                        field.append(quote);
                        i++;
                    } else {
                        quoted = false;
                    }
                } else if (c == escape && escape != quote && i + 1 < data.length()) {
                    field.append(data.charAt(++i));
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == quote) {
                quoted = true;
                fieldWasQuoted = true;
                continue;
            }
            if (c == delimiter) {
                row.add(csvField(field, fieldWasQuoted, options.nullMarker()));
                field.setLength(0);
                fieldWasQuoted = false;
                continue;
            }
            if (c == '\n') {
                row.add(csvField(field, fieldWasQuoted, options.nullMarker()));
                rows.add(row);
                row = new ArrayList<>();
                field.setLength(0);
                fieldWasQuoted = false;
                continue;
            }
            if (c != '\r') {
                field.append(c);
            }
        }
        if (field.length() > 0 || fieldWasQuoted || !row.isEmpty()) {
            row.add(csvField(field, fieldWasQuoted, options.nullMarker()));
            rows.add(row);
        }
        return rows;
    }

    private static String csvField(StringBuilder field, boolean quoted, String nullMarker) {
        if (!quoted) {
            if (nullMarker == null) {
                return field.isEmpty() ? null : field.toString();
            }
            if (field.toString().equals(nullMarker)) {
                return null;
            }
        }
        return field.toString();
    }

    private static String toRowLiteral(List<String> row) {
        StringBuilder literal = new StringBuilder("(");
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                literal.append(", ");
            }
            literal.append(toLiteral(row.get(i)));
        }
        return literal.append(')').toString();
    }

    private static String toLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        boolean escape = value.indexOf('\\') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\t') >= 0
                || value.indexOf('\b') >= 0
                || value.indexOf('\f') >= 0
                || value.indexOf('\0') >= 0;
        if (!escape) {
            return "'" + value.replace("'", "''") + "'";
        }
        StringBuilder literal = new StringBuilder("E'");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\'' -> literal.append("''");
                case '\\' -> literal.append("\\\\");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                case '\b' -> literal.append("\\b");
                case '\f' -> literal.append("\\f");
                case '\0' -> literal.append("\\000");
                default -> literal.append(c);
            }
        }
        return literal.append('\'').toString();
    }
}
