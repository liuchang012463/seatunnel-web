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
        List<String> statements = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        Deque<Opener> openers = new ArrayDeque<>();
        int i = 0;
        int length = script.length();

        while (i < length) {
            char c = script.charAt(i);
            char next = i + 1 < length ? script.charAt(i + 1) : '\0';

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
                int end = skipQuoted(script, i, c);
                current.append(script, i, end);
                i = end;
                continue;
            }

            if (c == '$' && isDollarQuoteStart(script, i)) {
                int end = skipDollarQuoted(script, i);
                current.append(script, i, end);
                releaseDollarQuotedCreates(openers);
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
                    openers.push(Opener.BLOCK);
                } else if ("DECLARE".equals(word) && isStatementStart(current)) {
                    openers.push(Opener.PENDING_UNIT);
                } else if ("CREATE".equals(word) && createsBody(script, wordEnd)) {
                    openers.push(Opener.PENDING_UNIT);
                } else if ("END".equals(word) && !openers.isEmpty()) {
                    String after = nextWord(script, wordEnd);
                    if (after == null || !NON_BLOCK_END.contains(after)) {
                        openers.pop();
                    }
                }

                current.append(script, i, wordEnd);
                i = wordEnd;
                continue;
            }

            if (c == ';' && openers.isEmpty()) {
                addIfMeaningful(statements, current.toString());
                current.setLength(0);
                i++;
                continue;
            }

            if (c == ';' && !openers.isEmpty() && openers.peek() == Opener.PENDING_UNIT) {
                // A body-bearing CREATE whose ';' arrives while still waiting
                // for its BEGIN/IS/AS has no body at all — the PostgreSQL
                // "CREATE TRIGGER ... EXECUTE PROCEDURE fn();" shape. Only a
                // following body keyword keeps the unit open.
                String after = nextSignificantWord(script, i + 1);
                if (after == null || (!"BEGIN".equals(after) && !"IS".equals(after) && !"AS".equals(after))) {
                    openers.pop();
                    addIfMeaningful(statements, current.toString());
                    current.setLength(0);
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

    private static String nextWord(String script, int from) {
        List<String> words = wordsAfter(script, from, 1);
        return words.isEmpty() ? null : words.get(0);
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

    private static void addIfMeaningful(List<String> statements, String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || isStatementStart(new StringBuilder(trimmed))) {
            return;
        }
        statements.add(trimmed);
    }
}
