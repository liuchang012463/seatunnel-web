package org.apache.seatunnel.web.api.offlineimport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Token-aware schema mapping for SQL dump statements. */
public final class SqlSchemaMapper {

    private SqlSchemaMapper() {
    }

    /** Rewrites source-qualified identifiers while preserving comments and ordinary string values. */
    public static String rewrite(String sql, String sourceSchema, String targetSchema) {
        if (sql == null || sourceSchema == null || targetSchema == null
                || sourceSchema.equals(targetSchema)) {
            return sql;
        }
        if (isDynamicTriggerBody(sql)) {
            throw new IllegalArgumentException("包含动态 SQL 的触发器无法安全重映射 Schema: EXECUTE");
        }
        Span routineBody = routineBody(sql);
        if (routineBody == null) {
            return rewriteCode(sql, sourceSchema, targetSchema);
        }
        String body = sql.substring(routineBody.contentStart(), routineBody.contentEnd());
        if (containsWord(body, "EXECUTE")) {
            throw new IllegalArgumentException("包含动态 SQL 的存储程序无法安全重映射 Schema: EXECUTE");
        }
        String prefix;
        String suffix;
        String rewrittenBody = body;
        if (routineBody.kind() == BodyKind.PLAIN) {
            return rewriteCode(sql, sourceSchema, targetSchema);
        } else if (routineBody.kind() == BodyKind.C) {
            prefix = sql.substring(0, routineBody.contentStart() - routineBody.delimiter().length());
            suffix = sql.substring(routineBody.contentEnd() + routineBody.delimiter().length());
        } else if (routineBody.kind() == BodyKind.SINGLE_QUOTED) {
            prefix = sql.substring(0, routineBody.contentStart() - 1);
            suffix = sql.substring(routineBody.contentEnd() + 1);
            String decoded = body.replace("''", "'");
            rewrittenBody = rewriteCode(decoded, sourceSchema, targetSchema).replace("'", "''");
        } else {
            prefix = sql.substring(0, routineBody.contentStart() - routineBody.delimiter().length());
            suffix = sql.substring(routineBody.contentEnd() + routineBody.delimiter().length());
            rewrittenBody = rewriteCode(body, sourceSchema, targetSchema);
        }
        if (routineBody.kind() == BodyKind.C) {
            return rewriteCode(sql, sourceSchema, targetSchema);
        }
        return rewriteCode(prefix, sourceSchema, targetSchema) + routineBody.delimiter() + rewrittenBody
                + routineBody.delimiter() + rewriteCode(suffix, sourceSchema, targetSchema);
    }

    /** Returns explicit schemas used as object targets by DDL/DML statements. */
    public static Set<String> writeSchemas(String sql) {
        List<Token> tokens = tokens(sql);
        Span routineBody = routineBody(sql);
        if (routineBody != null && routineBody.kind() != BodyKind.C) {
            String body = sql.substring(routineBody.contentStart(), routineBody.contentEnd());
            if (routineBody.kind() == BodyKind.SINGLE_QUOTED) {
                body = body.replace("''", "'");
            }
            tokens.addAll(tokens(body));
        }
        Set<String> schemas = new LinkedHashSet<>();
        boolean createIndexOrTrigger = containsWord(tokens, "CREATE")
                && (containsWord(tokens, "INDEX") || containsWord(tokens, "TRIGGER"));
        for (int i = 0; i < tokens.size(); i++) {
            String word = tokens.get(i).word();
            if ("CREATE".equals(word)) {
                int kind = nextObjectKind(tokens, i + 1);
                if (kind >= 0) {
                    if ("SCHEMA".equals(tokens.get(kind).word())) {
                        addNamedSchema(tokens, skipIfNotExists(tokens, kind + 1), schemas);
                    } else {
                        addQualifiedSchema(tokens, skipIfNotExists(tokens, kind + 1), schemas);
                        if ("INDEX".equals(tokens.get(kind).word()) || "TRIGGER".equals(tokens.get(kind).word())) {
                            int on = findWord(tokens, "ON", kind + 1);
                            if (on >= 0) {
                                addQualifiedSchema(tokens, on + 1, schemas);
                            }
                        }
                    }
                }
            } else if ("ALTER".equals(word) || "DROP".equals(word)) {
                int kind = nextObjectKind(tokens, i + 1);
                if (kind >= 0) {
                    int object = skipIfNotExists(tokens, kind + 1);
                    if ("SCHEMA".equals(tokens.get(kind).word())) {
                        addNamedSchema(tokens, object, schemas);
                    } else {
                        addQualifiedSchema(tokens, object, schemas);
                    }
                }
            } else if ("INSERT".equals(word) || "MERGE".equals(word)) {
                int into = findWord(tokens, "INTO", i + 1);
                if (into >= 0) {
                    addQualifiedSchema(tokens, skipOnly(tokens, into + 1), schemas);
                }
            } else if ("UPDATE".equals(word) || "COPY".equals(word)) {
                addQualifiedSchema(tokens, skipOnly(tokens, i + 1), schemas);
            } else if ("DELETE".equals(word) || "TRUNCATE".equals(word)) {
                if ("DELETE".equals(word)) {
                    int from = findWord(tokens, "FROM", i + 1);
                    if (from >= 0) {
                        addQualifiedSchema(tokens, skipOnly(tokens, from + 1), schemas);
                    }
                } else {
                    int table = i + 1;
                    if (table < tokens.size() && "TABLE".equals(tokens.get(table).word())) {
                        table++;
                    }
                    addQualifiedSchema(tokens, skipOnly(tokens, table), schemas);
                }
            } else if ("REFERENCES".equals(word)) {
                addQualifiedSchema(tokens, i + 1, schemas);
            } else if ("ON".equals(word) && createIndexOrTrigger) {
                addQualifiedSchema(tokens, i + 1, schemas);
            } else if ("TABLE".equals(word) && i > 0 && "COMMENT".equals(tokens.get(i - 1).word())) {
                addQualifiedSchema(tokens, i + 1, schemas);
            }
        }
        return schemas;
    }

    public static List<String> words(String sql) {
        return tokens(sql).stream().filter(Token::identifier).map(Token::word).toList();
    }

    /** Returns whether a stored program contains SQL whose object targets cannot be statically mapped. */
    public static boolean hasDynamicSql(String sql) {
        if (isDynamicTriggerBody(sql)) {
            return true;
        }
        Span body = routineBody(sql);
        return body != null && containsWord(sql.substring(body.contentStart(), body.contentEnd()), "EXECUTE");
    }

    public static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String rewriteCode(String sql, String source, String target) {
        StringBuilder out = new StringBuilder(sql.length());
        AliasInfo aliasInfo = aliases(sql);
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? sql.length() : end;
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && next == '*') {
                int end = skipBlockComment(sql, i);
                out.append(sql, i, end);
                i = end;
            } else if (c == '\'') {
                int end = skipQuoted(sql, i, '\'');
                String literal = sql.substring(i, end);
                String mapped = isSequenceReference(sql, i, end)
                        ? rewriteSequenceLiteral(literal, source, target) : literal;
                out.append(mapped);
                i = end;
            } else if (c == '"') {
                int end = skipQuoted(sql, i, '"');
                String identifier = sql.substring(i + 1, Math.max(i + 1, end - 1)).replace("\"\"", "\"");
                if (identifier.equals(source) && isSchemaDeclaration(sql, i)) {
                    out.append(quoteIdentifier(target));
                } else if (identifier.equals(source) && !aliasInfo.isAliasQualifier(identifier, i)
                        && followedByDot(sql, end)) {
                    out.append(quoteIdentifier(target));
                } else {
                    out.append(sql, i, end);
                }
                i = end;
            } else if (c == '$' && dollarTagEnd(sql, i) >= 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                if (close < 0) {
                    out.append(sql.substring(i));
                    break;
                }
                out.append(sql, i, close + tag.length());
                i = close + tag.length();
            } else if (isIdentifierStart(c)) {
                int end = i + 1;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String identifier = sql.substring(i, end);
                if (identifier.equalsIgnoreCase(source) && isSchemaDeclaration(sql, i)) {
                    out.append(quoteIdentifier(target));
                } else if (identifier.equalsIgnoreCase(source) && !aliasInfo.isAliasQualifier(identifier, i)
                        && followedByDot(sql, end)) {
                    out.append(quoteIdentifier(target));
                } else if (identifier.equalsIgnoreCase(source) && isSchemaDeclaration(sql, i)) {
                    out.append(quoteIdentifier(target));
                } else {
                    out.append(identifier);
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private record AliasInfo(Set<String> names, Set<Integer> schemaQualifierOffsets) {
        boolean isAliasQualifier(String identifier, int offset) {
            return names.contains(identifier.toUpperCase(Locale.ROOT)) && !schemaQualifierOffsets.contains(offset);
        }
    }

    private static AliasInfo aliases(String sql) {
        List<Token> sqlTokens = tokens(sql);
        Set<String> aliases = new LinkedHashSet<>();
        Set<Integer> schemaQualifierOffsets = new LinkedHashSet<>();
        Set<String> boundaries = Set.of("WHERE", "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER",
                "CROSS", "ON", "USING", "GROUP", "ORDER", "LIMIT", "OFFSET", "UNION", "SET",
                "VALUES", "RETURNING", "WHEN", "THEN", "ELSE", "END", "FROM", "INTO", "AS",
                "START", "CONNECT", "PARTITION", "WITH", "FOR", "WINDOW", "FETCH", "QUALIFY");
        for (int i = 0; i < sqlTokens.size(); i++) {
            String word = sqlTokens.get(i).word();
            if ("AS".equals(word) && i + 1 < sqlTokens.size() && sqlTokens.get(i + 1).identifier()) {
                aliases.add(sqlTokens.get(i + 1).value().toUpperCase(Locale.ROOT));
            }
            if (!Set.of("FROM", "JOIN", "UPDATE", "INTO").contains(word)) {
                continue;
            }
            int relation = skipOnly(sqlTokens, i + 1);
            if (relation >= sqlTokens.size() || !sqlTokens.get(relation).identifier()) {
                continue;
            }
            int afterRelation = relation + 1;
            if (afterRelation + 1 < sqlTokens.size() && ".".equals(sqlTokens.get(afterRelation).text())
                    && sqlTokens.get(afterRelation + 1).identifier()) {
                schemaQualifierOffsets.add(sqlTokens.get(relation).start());
                afterRelation += 2;
            }
            if (afterRelation < sqlTokens.size() && "AS".equals(sqlTokens.get(afterRelation).word())) {
                afterRelation++;
            }
            if (afterRelation < sqlTokens.size() && sqlTokens.get(afterRelation).identifier()
                    && !boundaries.contains(sqlTokens.get(afterRelation).word())) {
                aliases.add(sqlTokens.get(afterRelation).value().toUpperCase(Locale.ROOT));
            }
        }
        return new AliasInfo(aliases, schemaQualifierOffsets);
    }

    private static boolean isSequenceReference(String sql, int start, int end) {
        int after = skipWhitespace(sql, end);
        if (after + 2 < sql.length() && sql.startsWith("::", after)) {
            int typeStart = skipWhitespace(sql, after + 2);
            String type = wordAt(sql, typeStart);
            if ("regclass".equalsIgnoreCase(type)) {
                return true;
            }
        }
        String prefix = sql.substring(0, start).replaceAll("\\s+", " ");
        return prefix.matches("(?is).*?(?:pg_catalog|sys_catalog)\\s*\\.\\s*(?:nextval|currval|setval|lastval)\\s*\\(\\s*$")
                || prefix.matches("(?is).*?\\b(?:nextval|currval|setval|lastval)\\s*\\(\\s*$");
    }

    private static String rewriteSequenceLiteral(String literal, String source, String target) {
        if (literal.length() < 2 || literal.charAt(0) != '\'') {
            return literal;
        }
        String value = literal.substring(1, literal.length() - 1).replace("''", "'").trim();
        int dot = qualifiedNameDot(value);
        if (dot <= 0 || dot + 1 >= value.length()) {
            return literal;
        }
        String schema = value.substring(0, dot).trim();
        String schemaValue = schema.length() >= 2 && schema.charAt(0) == '"'
                && schema.charAt(schema.length() - 1) == '"'
                ? schema.substring(1, schema.length() - 1).replace("\"\"", "\"") : schema;
        if (!schemaValue.equalsIgnoreCase(source)) {
            return literal;
        }
        String mapped = quoteIdentifier(target) + "." + value.substring(dot + 1).trim();
        return "'" + mapped.replace("'", "''") + "'";
    }

    private static int qualifiedNameDot(String value) {
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == '.' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSchemaDeclaration(String sql, int at) {
        String prefix = sql.substring(0, at).replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ")
                .trim().toUpperCase(Locale.ROOT);
        return prefix.matches("(?s).*\\b(?:CREATE|ALTER|DROP)\\s+SCHEMA(?:\\s+IF\\s+NOT\\s+EXISTS)?$");
    }

    private static boolean followedByDot(String sql, int from) {
        int i = skipWhitespace(sql, from);
        return i < sql.length() && sql.charAt(i) == '.';
    }

    private enum BodyKind { SINGLE_QUOTED, DOLLAR_QUOTED, PLAIN, C }

    private record Span(int contentStart, int contentEnd, BodyKind kind, String delimiter) {
    }

    private static Span routineBody(String sql) {
        List<String> words = words(sql);
        if (words.isEmpty() || !"CREATE".equals(words.get(0))
                || words.stream().noneMatch(word -> Set.of("FUNCTION", "PROCEDURE", "PACKAGE").contains(word))) {
            return null;
        }
        boolean cBody = hasLanguageC(words);
        int i = 0;
        int parentheses = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? sql.length() : nl + 1;
            } else if (c == '/' && next == '*') {
                i = skipBlockComment(sql, i);
            } else if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
            } else if (c == '$' && dollarTagEnd(sql, i) >= 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                i = close < 0 ? sql.length() : close + tag.length();
            } else if (isIdentifierStart(c)) {
                int end = i + 1;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) end++;
                String word = sql.substring(i, end);
                if (parentheses == 0 && ("AS".equalsIgnoreCase(word) || "IS".equalsIgnoreCase(word))) {
                    int body = skipWhitespaceAndComments(sql, end);
                    if (body < sql.length() && sql.charAt(body) == '\'') {
                        return new Span(body + 1, Math.max(body + 1, skipQuoted(sql, body, '\'') - 1),
                                cBody ? BodyKind.C : BodyKind.SINGLE_QUOTED, "'");
                    }
                    if (body < sql.length() && sql.charAt(body) == '$') {
                        int tagEnd = dollarTagEnd(sql, body);
                        if (tagEnd >= 0) {
                            String tag = sql.substring(body, tagEnd);
                            int close = sql.indexOf(tag, tagEnd);
                            if (close >= 0) {
                                return new Span(tagEnd, close, cBody ? BodyKind.C : BodyKind.DOLLAR_QUOTED, tag);
                            }
                        }
                    }
                    return new Span(body, sql.length(), cBody ? BodyKind.C : BodyKind.PLAIN, "");
                }
                i = end;
            } else if (c == '(') {
                parentheses++;
                i++;
            } else if (c == ')') {
                parentheses = Math.max(0, parentheses - 1);
                i++;
            } else {
                i++;
            }
        }
        return null;
    }

    private static boolean hasLanguageC(List<String> words) {
        for (int i = 0; i + 1 < words.size(); i++) {
            if ("LANGUAGE".equals(words.get(i))
                    && ("C".equals(words.get(i + 1)) || "INTERNAL".equals(words.get(i + 1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDynamicTriggerBody(String sql) {
        List<Token> tokens = tokens(sql);
        int trigger = nextObjectKind(tokens, 1);
        if (tokens.isEmpty() || !"CREATE".equals(tokens.get(0).word()) || trigger < 0
                || !"TRIGGER".equals(tokens.get(trigger).word())) {
            return false;
        }
        int bodyStart = -1;
        for (int i = trigger + 1; i < tokens.size(); i++) {
            String word = tokens.get(i).word();
            if ("DECLARE".equals(word) || "BEGIN".equals(word)) {
                bodyStart = i;
                break;
            }
        }
        if (bodyStart < 0) {
            return false;
        }
        return tokens.subList(bodyStart, tokens.size()).stream().anyMatch(token -> "EXECUTE".equals(token.word()));
    }

    private static boolean containsWord(String sql, String word) {
        return words(sql).contains(word);
    }

    private static boolean containsWord(List<Token> tokens, String word) {
        return tokens.stream().anyMatch(token -> word.equals(token.word()));
    }

    private static int nextObjectKind(List<Token> tokens, int from) {
        int i = skipCreateModifiers(tokens, from);
        if (i < 0 || i >= tokens.size()) return -1;
        return tokens.get(i).identifier() ? i : -1;
    }

    private static int skipCreateModifiers(List<Token> tokens, int from) {
        int i = from;
        while (i < tokens.size() && Set.of("OR", "REPLACE", "TEMP", "TEMPORARY", "UNLOGGED", "UNIQUE",
                "MATERIALIZED", "GLOBAL", "LOCAL", "EDITIONABLE", "NONEDITIONABLE", "FORCE", "ONLY")
                .contains(tokens.get(i).word())) {
            i++;
        }
        return i;
    }

    private static int skipIfNotExists(List<Token> tokens, int from) {
        int i = from;
        if (i < tokens.size() && "IF".equals(tokens.get(i).word())) i++;
        if (i < tokens.size() && "NOT".equals(tokens.get(i).word())) i++;
        if (i < tokens.size() && "EXISTS".equals(tokens.get(i).word())) i++;
        return skipOnly(tokens, i);
    }

    private static int skipOnly(List<Token> tokens, int from) {
        return from < tokens.size() && "ONLY".equals(tokens.get(from).word()) ? from + 1 : from;
    }

    private static int findWord(List<Token> tokens, String word, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (word.equals(tokens.get(i).word())) return i;
            if (";".equals(tokens.get(i).text())) return -1;
        }
        return -1;
    }

    private static void addQualifiedSchema(List<Token> tokens, int at, Set<String> schemas) {
        if (at >= 0 && at + 2 < tokens.size()
                && tokens.get(at).identifier() && ".".equals(tokens.get(at + 1).text())
                && tokens.get(at + 2).identifier()) {
            schemas.add(tokens.get(at).value());
        }
    }

    private static void addNamedSchema(List<Token> tokens, int at, Set<String> schemas) {
        if (at >= 0 && at < tokens.size() && tokens.get(at).identifier()) {
            schemas.add(tokens.get(at).value());
        }
    }

    private record Token(String text, String value, boolean identifier, int start, int end) {
        String word() {
            return identifier ? value.toUpperCase(Locale.ROOT) : text;
        }
    }

    private static List<Token> tokens(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? sql.length() : nl + 1;
            } else if (c == '/' && next == '*') {
                i = skipBlockComment(sql, i);
            } else if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
            } else if (c == '"') {
                int end = skipQuoted(sql, i, '"');
                String value = sql.substring(i + 1, Math.max(i + 1, end - 1)).replace("\"\"", "\"");
                tokens.add(new Token(sql.substring(i, end), value, true, i, end));
                i = end;
            } else if (c == '$' && dollarTagEnd(sql, i) >= 0) {
                int tagEnd = dollarTagEnd(sql, i);
                int close = sql.indexOf(sql.substring(i, tagEnd), tagEnd);
                i = close < 0 ? sql.length() : close + tagEnd - i;
            } else if (isIdentifierStart(c)) {
                int end = i + 1;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) end++;
                tokens.add(new Token(sql.substring(i, end), sql.substring(i, end), true, i, end));
                i = end;
            } else {
                tokens.add(new Token(String.valueOf(c), String.valueOf(c), false, i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    private static int skipBlockComment(String sql, int start) {
        int depth = 1;
        int i = start + 2;
        while (i < sql.length() && depth > 0) {
            if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                depth++;
                i += 2;
            } else if (i + 1 < sql.length() && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                depth--;
                i += 2;
            } else i++;
        }
        return i;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i += 2;
                else return i + 1;
            } else i++;
        }
        return sql.length();
    }

    private static int dollarTagEnd(String sql, int start) {
        int i = start + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        return i < sql.length() && sql.charAt(i) == '$' ? i + 1 : -1;
    }

    private static int skipWhitespace(String sql, int from) {
        int i = from;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) i++;
        return i;
    }

    private static int skipWhitespaceAndComments(String sql, int from) {
        int i = from;
        while (i < sql.length()) {
            if (Character.isWhitespace(sql.charAt(i))) i++;
            else if (i + 1 < sql.length() && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? sql.length() : nl + 1;
            } else if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
            } else break;
        }
        return i;
    }

    private static String wordAt(String sql, int from) {
        int end = from;
        while (end < sql.length() && isIdentifierPart(sql.charAt(end))) end++;
        return sql.substring(from, end);
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
