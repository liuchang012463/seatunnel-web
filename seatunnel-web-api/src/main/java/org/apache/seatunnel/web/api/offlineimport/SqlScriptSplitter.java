package org.apache.seatunnel.web.api.offlineimport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits a KingbaseES/Dameng SQL dump script into executable statements.
 *
 * <p>Handles line/block comments, single/double quoted strings, dollar-quoted
 * bodies ({@code $$ ... $$}, {@code $tag$ ... $tag$}), and PL/SQL blocks.
 * {@code BEGIN}, statement-leading {@code DECLARE}, {@code CASE}, and
 * body-bearing {@code CREATE PROCEDURE|FUNCTION|TRIGGER} open a block that
 * only closes on its matching {@code END}; {@code END IF|LOOP|WHILE} never
 * close blocks and {@code END CASE} closes a CASE statement. A dollar-quoted
 * body whose closing END lives inside the quotes (the PostgreSQL/kingbase
 * function style) releases its CREATE opener when the quote ends.</p>
 */
public final class SqlScriptSplitter {

    /** One executable SQL statement, a PostgreSQL COPY payload, or a client directive. */
    public record Statement(String sql, String copyData, String directive) {
        public static Statement sql(String sql) {
            return new Statement(sql, null, null);
        }

        public static Statement copy(String sql, String data) {
            return new Statement(sql, data, null);
        }

        public static Statement directive(String directive) {
            return new Statement(null, null, directive);
        }

        public boolean isCopy() {
            return copyData != null;
        }

        public boolean isDirective() {
            return directive != null;
        }
    }

    private enum Opener {
        /** A DECLARE/CREATE unit whose terminating BEGIN..END has not started yet. */
        PENDING_UNIT, BLOCK
    }

    private static final Set<String> CREATE_BODY_PREFIX = Set.of("OR", "REPLACE", "EDITIONABLE", "NONEDITIONABLE");
    private static final Set<String> CREATE_BODY_KIND = Set.of("PROCEDURE", "FUNCTION", "TRIGGER", "PACKAGE");
    private static final Set<String> NON_BLOCK_END = Set.of("IF", "LOOP", "WHILE");

    private SqlScriptSplitter() {
    }

    public static List<String> split(String script) {
        return parse(script).stream()
                .filter(statement -> !statement.isDirective())
                .map(Statement::sql)
                .toList();
    }

    /**
     * Splits SQL while keeping {@code COPY ... FROM stdin} data opaque. Its
     * payload is preserved verbatim up to the standalone {@code \\.} line;
     * client commands are returned separately for preflight validation.
     */
    public static List<Statement> parse(String script) {
        List<Statement> statements = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        Deque<Opener> openers = new ArrayDeque<>();
        boolean skipCaseAfterEndCase = false;
        int i = 0;
        int length = script.length();

        while (i < length) {
            char c = script.charAt(i);
            char next = i + 1 < length ? script.charAt(i + 1) : '\0';

            if ((c == '\\' || c == '@') && isLineStart(script, i) && openers.isEmpty()) {
                int end = script.indexOf('\n', i);
                end = end < 0 ? length : end;
                addIfMeaningful(statements, current.toString());
                current.setLength(0);
                statements.add(Statement.directive(script.substring(i, end).trim()));
                i = end < length ? end + 1 : end;
                continue;
            }

            if (c == '/' && isStandaloneSlashLine(script, i)) {
                addIfMeaningful(statements, current.toString());
                current.setLength(0);
                openers.clear();
                int end = script.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
                continue;
            }

            if (c == '-' && next == '-') {
                int end = script.indexOf('\n', i);
                end = end < 0 ? length : end;
                current.append(script, i, end);
                i = end;
                continue;
            }

            if (c == '/' && next == '*') {
                int end = skipBlockComment(script, i);
                current.append(script, i, end);
                i = end;
                continue;
            }

            if (c == '\'' || c == '"') {
                boolean createFunctionBody = c == '\'' && isCreateFunctionBodyStart(current);
                int end = skipQuoted(script, i, c);
                current.append(script, i, end);
                if (createFunctionBody) {
                    releaseDollarQuotedCreates(openers);
                }
                i = end;
                continue;
            }

            if (c == '$' && isDollarQuoteStart(script, i)) {
                boolean createBody = isCreateBodyStart(current);
                int end = skipDollarQuoted(script, i);
                current.append(script, i, end);
                if (createBody) {
                    releaseDollarQuotedCreates(openers);
                }
                i = end;
                continue;
            }

            if (Character.isLetter(c)) {
                int wordEnd = i;
                while (wordEnd < length
                        && (Character.isLetterOrDigit(script.charAt(wordEnd)) || script.charAt(wordEnd) == '_')) {
                    wordEnd++;
                }
                String word = script.substring(i, wordEnd).toUpperCase(Locale.ROOT);

                if ("BEGIN".equals(word)) {
                    skipCaseAfterEndCase = false;
                    if (isTransactionBegin(script, wordEnd)) {
                        // "BEGIN;" / "BEGIN WORK;" opens a transaction, not a
                        // block; pushing here would swallow the whole script.
                    } else {
                        // A BEGIN right after a DECLARE/CREATE unit starts that unit's
                        // body instead of opening an independent block.
                        if (!openers.isEmpty() && openers.peek() == Opener.PENDING_UNIT) {
                            openers.pop();
                        }
                        openers.push(Opener.BLOCK);
                    }
                } else if ("CASE".equals(word)) {
                    if (skipCaseAfterEndCase) {
                        skipCaseAfterEndCase = false;
                    } else {
                        openers.push(Opener.BLOCK);
                    }
                } else if ("DECLARE".equals(word) && isStatementStart(current)) {
                    skipCaseAfterEndCase = false;
                    openers.push(Opener.PENDING_UNIT);
                } else if ("CREATE".equals(word) && createsBody(script, wordEnd)) {
                    skipCaseAfterEndCase = false;
                    openers.push(Opener.PENDING_UNIT);
                } else if ("END".equals(word) && !openers.isEmpty()) {
                    String after = nextSignificantWord(script, wordEnd);
                    if (after == null || !NON_BLOCK_END.contains(after)) {
                        openers.pop();
                    }
                    skipCaseAfterEndCase = "CASE".equals(after);
                } else {
                    skipCaseAfterEndCase = false;
                }

                current.append(script, i, wordEnd);
                i = wordEnd;
                continue;
            }

            if (c == ';' && openers.isEmpty()) {
                if (isCopyFromStdin(current.toString())) {
                    int dataStart = i + 1;
                    if (dataStart < length && script.charAt(dataStart) == '\r') {
                        dataStart++;
                    }
                    if (dataStart < length && script.charAt(dataStart) == '\n') {
                        dataStart++;
                    }
                    CopyPayload payload = readCopyPayload(script, dataStart);
                    statements.add(Statement.copy(current.toString().trim(), payload.data()));
                    current.setLength(0);
                    i = payload.nextIndex();
                    continue;
                }
                addIfMeaningful(statements, current.toString());
                current.setLength(0);
                i++;
                continue;
            }

            if (c == ';' && !openers.isEmpty() && openers.peek() == Opener.PENDING_UNIT) {
                // A bodyless PostgreSQL trigger ends at the function call's
                // semicolon. Other pending units may contain multiple
                // declaration statements before their BEGIN, so keep each
                // declaration semicolon in the SQL text.
                if (isBodylessTrigger(current)) {
                    openers.pop();
                    addIfMeaningful(statements, current.toString());
                    current.setLength(0);
                } else {
                    current.append(';');
                }
                i++;
                continue;
            }

            current.append(c);
            i++;
        }
        addIfMeaningful(statements, current.toString());
        return statements;
    }

    private static boolean isCopyFromStdin(String sql) {
        List<String> words = sqlWords(sql);
        if (words.size() < 3 || !"COPY".equals(words.get(0))) {
            return false;
        }
        for (int i = 1; i + 1 < words.size(); i++) {
            if ("FROM".equals(words.get(i)) && "STDIN".equals(words.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private record CopyPayload(String data, int nextIndex) {
    }

    private static CopyPayload readCopyPayload(String script, int from) {
        StringBuilder data = new StringBuilder();
        int lineStart = from;
        while (lineStart <= script.length()) {
            int lineEnd = script.indexOf('\n', lineStart);
            boolean hasNewline = lineEnd >= 0;
            if (!hasNewline) {
                lineEnd = script.length();
            }
            int contentEnd = lineEnd > lineStart && script.charAt(lineEnd - 1) == '\r'
                    ? lineEnd - 1 : lineEnd;
            if ("\\.".equals(script.substring(lineStart, contentEnd))) {
                return new CopyPayload(data.toString(), hasNewline ? lineEnd + 1 : lineEnd);
            }
            data.append(script, lineStart, lineEnd);
            if (hasNewline) {
                data.append('\n');
                lineStart = lineEnd + 1;
            } else {
                break;
            }
        }
        throw new IllegalArgumentException("COPY FROM STDIN 数据段缺少独立的 \\. 结束行");
    }

    private static boolean isLineStart(String script, int index) {
        int lineStart = script.lastIndexOf('\n', index - 1) + 1;
        for (int i = lineStart; i < index; i++) {
            if (!Character.isWhitespace(script.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Recognizes the PostgreSQL trigger form without an inline body. */
    private static boolean isBodylessTrigger(StringBuilder current) {
        List<String> words = sqlWords(current.toString());
        int triggerIndex = createKindIndex(words, "TRIGGER");
        if (triggerIndex < 0) {
            return false;
        }
        for (int i = triggerIndex + 1; i + 1 < words.size(); i++) {
            if ("EXECUTE".equals(words.get(i))
                    && ("PROCEDURE".equals(words.get(i + 1)) || "FUNCTION".equals(words.get(i + 1)))) {
                boolean blockStartedBeforeExecute = words.subList(triggerIndex + 1, i).stream()
                        .anyMatch(word -> "DECLARE".equals(word) || "BEGIN".equals(word));
                return !blockStartedBeforeExecute;
            }
        }
        return false;
    }

    private static boolean isCreateFunctionBodyStart(StringBuilder current) {
        return isCreateBodyStart(current);
    }

    private static boolean isCreateBodyStart(StringBuilder current) {
        List<String> words = sqlWords(current.toString());
        if (words.isEmpty()) {
            return false;
        }
        for (String kind : CREATE_BODY_KIND) {
            if (createKindIndex(words, kind) >= 0
                    && ("AS".equals(words.get(words.size() - 1)) || "IS".equals(words.get(words.size() - 1)))) {
                return true;
            }
        }
        return false;
    }

    private static int createKindIndex(List<String> words, String kind) {
        if (words.isEmpty() || !"CREATE".equals(words.get(0))) {
            return -1;
        }
        int i = 1;
        while (i < words.size() && CREATE_BODY_PREFIX.contains(words.get(i))) {
            i++;
        }
        return i < words.size() && kind.equals(words.get(i)) ? i : -1;
    }

    /** Tokenizes SQL words while ignoring comments, strings and quoted identifiers. */
    private static List<String> sqlWords(String sql) {
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end + 1;
            } else if (c == '/' && next == '*') {
                i = skipBlockComment(sql, i);
            } else if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
            } else if (c == '$' && isDollarQuoteStart(sql, i)) {
                i = skipDollarQuoted(sql, i);
            } else if (Character.isLetter(c)) {
                int end = i + 1;
                while (end < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                words.add(sql.substring(i, end).toUpperCase(Locale.ROOT));
                i = end;
            } else {
                i++;
            }
        }
        return words;
    }

    /** Returns true when a slash is the only non-whitespace token on its line. */
    private static boolean isStandaloneSlashLine(String script, int slash) {
        int lineStart = script.lastIndexOf('\n', slash - 1) + 1;
        for (int i = lineStart; i < slash; i++) {
            if (!Character.isWhitespace(script.charAt(i))) {
                return false;
            }
        }
        int lineEnd = script.indexOf('\n', slash + 1);
        if (lineEnd < 0) {
            lineEnd = script.length();
        }
        int i = slash + 1;
        while (i < lineEnd && Character.isWhitespace(script.charAt(i))) {
            i++;
        }
        return i == lineEnd || (i + 1 < lineEnd && script.charAt(i) == '-' && script.charAt(i + 1) == '-');
    }

    /**
     * A dollar-quoted function body contains its own closing END inside the
     * quotes, where the scanner cannot see it; the pending unit it belonged
     * to must therefore be released once the quoted body ends.
     */
    private static void releaseDollarQuotedCreates(Deque<Opener> openers) {
        while (!openers.isEmpty() && openers.peek() == Opener.PENDING_UNIT) {
            openers.pop();
        }
    }

    /**
     * True when the words after CREATE match [OR REPLACE] [EDITIONABLE] PROCEDURE|FUNCTION|TRIGGER|PACKAGE.
     */
    private static boolean createsBody(String script, int from) {
        List<String> words = wordsAfter(script, from, 4);
        for (String word : words) {
            if (CREATE_BODY_KIND.contains(word)) {
                return true;
            }
            if (!CREATE_BODY_PREFIX.contains(word)) {
                return false;
            }
        }
        return false;
    }

    /**
     * True for {@code BEGIN;} / {@code BEGIN WORK;} / {@code BEGIN TRANSACTION;} — the
     * PostgreSQL-lineage transaction control, which carries no block body.
     */
    private static boolean isTransactionBegin(String script, int from) {
        int i = skipWhitespace(script, from);
        String word = wordAt(script, i);
        if ("WORK".equals(word) || "TRANSACTION".equals(word)) {
            i = skipWhitespace(script, i + word.length());
        }
        return i < script.length() && script.charAt(i) == ';';
    }

    /** The next word skipping whitespace and both comment styles, or null at end of input. */
    private static String nextSignificantWord(String script, int from) {
        int i = from;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                int nl = script.indexOf('\n', i);
                if (nl < 0) {
                    return null;
                }
                i = nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                int close = script.indexOf("*/", i + 2);
                if (close < 0) {
                    return null;
                }
                i = close + 2;
                continue;
            }
            return wordAt(script, i);
        }
        return null;
    }

    private static int skipWhitespace(String script, int from) {
        int i = from;
        while (i < script.length() && Character.isWhitespace(script.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String wordAt(String script, int from) {
        int i = from;
        while (i < script.length()
                && (Character.isLetterOrDigit(script.charAt(i)) || script.charAt(i) == '_')) {
            i++;
        }
        return from == i ? null : script.substring(from, i).toUpperCase(Locale.ROOT);
    }

    private static List<String> wordsAfter(String script, int from, int count) {
        List<String> words = new ArrayList<>();
        int i = from;
        while (words.size() < count && i < script.length()) {
            while (i < script.length() && Character.isWhitespace(script.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < script.length()
                    && (Character.isLetterOrDigit(script.charAt(i)) || script.charAt(i) == '_')) {
                i++;
            }
            if (start == i) {
                break;
            }
            words.add(script.substring(start, i).toUpperCase(Locale.ROOT));
        }
        return words;
    }

    /** Block comments nest in PostgreSQL-lineage dialects; mirror that here. */
    private static int skipBlockComment(String script, int start) {
        Deque<Character> depth = new ArrayDeque<>();
        depth.push('*');
        int i = start + 2;
        while (i < script.length() && !depth.isEmpty()) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            if (c == '/' && next == '*') {
                depth.push('*');
                i += 2;
            } else if (c == '*' && next == '/') {
                depth.pop();
                i += 2;
            } else {
                i++;
            }
        }
        return i;
    }

    /** Skips a quoted region; a doubled quote (e.g. '') is an escaped quote. */
    private static int skipQuoted(String script, int start, char quote) {
        int i = start + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == quote) {
                char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
                if (next == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static boolean isDollarQuoteStart(String script, int i) {
        int j = i + 1;
        while (j < script.length() && (Character.isLetterOrDigit(script.charAt(j)) || script.charAt(j) == '_')) {
            j++;
        }
        return j < script.length() && script.charAt(j) == '$';
    }

    private static int skipDollarQuoted(String script, int start) {
        int tagEnd = script.indexOf('$', start + 1);
        if (tagEnd < 0) {
            return script.length();
        }
        String tag = script.substring(start, tagEnd + 1);
        int close = script.indexOf(tag, tagEnd + 1);
        return close < 0 ? script.length() : close + tag.length();
    }

    /** True when nothing but whitespace/comments has accumulated for the current statement. */
    private static boolean isStatementStart(StringBuilder current) {
        for (int i = 0; i < current.length(); i++) {
            char c = current.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '-' && i + 1 < current.length() && current.charAt(i + 1) == '-') {
                int nl = current.indexOf("\n", i);
                if (nl < 0) {
                    return true;
                }
                i = nl;
                continue;
            }
            if (c == '/' && i + 1 < current.length() && current.charAt(i + 1) == '*') {
                int close = current.indexOf("*/", i + 2);
                if (close < 0) {
                    return true;
                }
                i = close + 1;
                continue;
            }
            return false;
        }
        return true;
    }

    private static void addIfMeaningful(List<Statement> statements, String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || isStatementStart(new StringBuilder(trimmed))) {
            return;
        }
        statements.add(Statement.sql(trimmed));
    }
}
