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
 * <p>The splitter understands the shapes found in real database exports:
 * line/block comments, single/double quoted strings, {@code E'...'} strings,
 * Oracle-style {@code q'[...]'} strings, dollar-quoted bodies
 * ({@code $$ ... $$}, {@code $tag$ ... $tag$}), PL/SQL declaration sections,
 * nested blocks, packages/type bodies, {@code CASE ... END CASE}, transaction
 * control such as {@code BEGIN TRANSACTION ISOLATION LEVEL ...}, and
 * {@code COPY ... FROM stdin} data blocks.</p>
 *
 * <p>Statements are returned without their terminating semicolon. A COPY
 * block is returned as one statement, including its raw data lines and the
 * closing {@code \.} marker. The typed parse API keeps COPY data separate
 * for native driver execution and validates client directives before import.</p>
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

    private enum UnitKind {
        PROCEDURE,
        FUNCTION,
        TRIGGER,
        PACKAGE,
        PACKAGE_BODY,
        TYPE_BODY,
        DECLARE_BLOCK,
        ANONYMOUS
    }

    /** State of one PL/SQL unit (procedure/function/package/anonymous block). */
    private static final class Unit {
        private final UnitKind kind;
        private final int createKindWordEnd;
        private boolean declarationSection;
        private boolean bodyStarted;
        private boolean bodyClauseSeen;
        private boolean literalBody;
        private boolean expectBodyLiteral;
        private boolean unitEndSeen;
        private int blockDepth;

        private Unit(UnitKind kind, boolean declarationSection, int createKindWordEnd) {
            this.kind = kind;
            this.declarationSection = declarationSection;
            this.createKindWordEnd = createKindWordEnd;
        }
    }

    /** A local procedure/function declared inside another unit. */
    private static final class NestedUnit {
        private boolean bodyClauseSeen;
        private boolean bodyStarted;
        private int blockDepth;
    }

    private record WordToken(String word, int start, int end) {
    }

    private record CreateInfo(UnitKind kind, int kindWordEnd) {
    }

    private static final Set<String> CREATE_BODY_PREFIX =
            Set.of("OR", "REPLACE", "EDITIONABLE", "NONEDITIONABLE");
    private static final Set<String> NON_BLOCK_END = Set.of("IF", "LOOP", "WHILE");

    private SqlScriptSplitter() {
    }

    public static List<String> split(String script) {
        return parse(script).stream()
                .filter(statement -> !statement.isDirective())
                .map(statement -> statement.isCopy()
                        ? statement.sql() + ";\n" + statement.copyData() + "\\."
                        : statement.sql())
                .toList();
    }

    /** Parses executable SQL, opaque COPY payloads, and client directives separately. */
    public static List<Statement> parse(String script) {
        List<Statement> statements = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        Unit unit = null;
        Deque<NestedUnit> nestedUnits = new ArrayDeque<>();
        int caseDepth = 0;
        int parenDepth = 0;
        int i = 0;
        int length = script.length();

        while (i < length) {
            char c = script.charAt(i);
            char next = i + 1 < length ? script.charAt(i + 1) : '\0';

            if (c == '\\' && isStatementStart(current) && isLineStart(script, i)) {
                int lineEnd = script.indexOf('\n', i);
                if (lineEnd < 0) {
                    lineEnd = length;
                }
                statements.add(Statement.directive(script.substring(i, lineEnd).trim()));
                current.setLength(0);
                i = lineEnd;
                continue;
            }

            if (c == '-' && next == '-') {
                int end = skipLineComment(script, i);
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

            // SQL*Plus / disql terminator: a line that contains only "/" after
            // a completed statement. A slash inside an expression stays a
            // division operator.
            if (c == '/' && isStatementStart(current) && isSlashTerminator(script, i)) {
                i = skipLine(script, i);
                continue;
            }

            // E'...' and N'...' prefixes are part of the literal, not a word.
            if ((c == 'E' || c == 'e') && next == '\''
                    && (i == 0 || !isIdentifierChar(script.charAt(i - 1)))) {
                int end = skipQuoted(script, i + 1, '\'', true);
                markLiteralBody(unit);
                current.append(script, i, end);
                i = end;
                continue;
            }
            if ((c == 'N' || c == 'n') && next == '\''
                    && (i == 0 || !isIdentifierChar(script.charAt(i - 1)))) {
                int end = skipQuoted(script, i + 1, '\'', false);
                markLiteralBody(unit);
                current.append(script, i, end);
                i = end;
                continue;
            }

            if (c == '\'' || c == '"') {
                boolean backslashEscapes = c == '\'' && isEStringPrefix(script, i);
                int end = skipQuoted(script, i, c, backslashEscapes);
                if (c == '\'') {
                    // A double-quoted token is an identifier, not a literal
                    // body after AS/IS.
                    markLiteralBody(unit);
                }
                current.append(script, i, end);
                i = end;
                continue;
            }

            // Oracle/Dameng NQ'[...]' is an N-prefixed alternative quote.
            if ((c == 'N' || c == 'n') && (next == 'Q' || next == 'q')
                    && i + 2 < length && script.charAt(i + 2) == '\''
                    && (i == 0 || !isIdentifierChar(script.charAt(i - 1)))) {
                int end = skipQQuote(script, i + 1);
                markLiteralBody(unit);
                current.append(script, i, end);
                i = end;
                continue;
            }

            if ((c == 'q' || c == 'Q') && next == '\'' && isQQuoteStart(script, i)) {
                int end = skipQQuote(script, i);
                markLiteralBody(unit);
                current.append(script, i, end);
                i = end;
                continue;
            }

            if (c == '$' && isDollarQuoteStart(script, i)) {
                int end = skipDollarQuoted(script, i);
                markLiteralBody(unit);
                current.append(script, i, end);
                i = end;
                continue;
            }

            if (Character.isLetter(c) || c == '_' || c == '$') {
                int wordEnd = i;
                while (wordEnd < length && isIdentifierChar(script.charAt(wordEnd))) {
                    wordEnd++;
                }
                String word = script.substring(i, wordEnd).toUpperCase(Locale.ROOT);

                if ("CREATE".equals(word)) {
                    CreateInfo info = createInfo(script, wordEnd);
                    if (info != null) {
                        unit = new Unit(
                                info.kind(),
                                info.kind() != UnitKind.TRIGGER,
                                info.kindWordEnd());
                    }
                } else if ("DECLARE".equals(word)) {
                    if (unit == null) {
                        if (isStatementStart(current)) {
                            unit = new Unit(UnitKind.DECLARE_BLOCK, true, -1);
                        }
                    } else if (!unit.bodyStarted) {
                        unit.declarationSection = true;
                    }
                } else if ("BEGIN".equals(word)) {
                    if (!isTransactionBegin(script, wordEnd)) {
                        if (unit == null) {
                            // Only a statement-leading BEGIN starts an
                            // anonymous block; BEGIN used as an identifier
                            // inside a SELECT must stay part of the statement.
                            if (isStatementStart(current)) {
                                unit = new Unit(UnitKind.ANONYMOUS, false, -1);
                                unit.bodyStarted = true;
                                unit.blockDepth = 1;
                            }
                        } else if (!nestedUnits.isEmpty()) {
                            NestedUnit nested = nestedUnits.peek();
                            if (!nested.bodyStarted) {
                                nested.bodyStarted = true;
                                nested.blockDepth = 1;
                            } else {
                                nested.blockDepth++;
                            }
                        } else if (!unit.bodyStarted) {
                            unit.bodyStarted = true;
                            unit.blockDepth = 1;
                        } else {
                            unit.blockDepth++;
                        }
                    }
                } else if ("CASE".equals(word)) {
                    caseDepth++;
                } else if (("PROCEDURE".equals(word) || "FUNCTION".equals(word))
                        && parenDepth == 0) {
                    if (unit != null
                            && wordEnd != unit.createKindWordEnd
                            && inDeclarationSection(unit, nestedUnits)) {
                        nestedUnits.push(new NestedUnit());
                    }
                } else if (("AS".equals(word) || "IS".equals(word)) && parenDepth == 0) {
                    if (!nestedUnits.isEmpty()) {
                        NestedUnit nested = nestedUnits.peek();
                        if (!nested.bodyStarted) {
                            // A local subprogram's declaration/body starts
                            // here; a bare "PROCEDURE p;" has no IS/AS and is
                            // dropped at its semicolon as a forward declaration.
                            nested.bodyClauseSeen = true;
                        }
                        // An AS/IS inside the nested body (column alias,
                        // CAST, subquery alias) must not leak into the outer
                        // unit's literal-body detection.
                    } else if (unit != null && !unit.bodyStarted) {
                        if (unit.kind == UnitKind.PACKAGE
                                || unit.kind == UnitKind.PACKAGE_BODY
                                || unit.kind == UnitKind.TYPE_BODY) {
                            unit.bodyStarted = true;
                        } else if (unit.kind == UnitKind.PROCEDURE
                                || unit.kind == UnitKind.FUNCTION) {
                            // Only the first top-level AS/IS after the
                            // parameter list is the body clause. Later ones
                            // (cursor queries, SELECT aliases) are content.
                            if (!unit.bodyClauseSeen) {
                                unit.bodyClauseSeen = true;
                                unit.expectBodyLiteral = true;
                            }
                        }
                        // DECLARE_BLOCK/TRIGGER declarations use BEGIN bodies;
                        // AS/IS there belongs to cursors/types, not a literal.
                    }
                } else if ("END".equals(word)) {
                    WordToken after = nextSignificantWord(script, wordEnd);
                    if (after != null && NON_BLOCK_END.contains(after.word())) {
                        // END IF / END LOOP / END WHILE close a control structure,
                        // not the surrounding BEGIN block.
                    } else if (after != null && "CASE".equals(after.word())) {
                        if (caseDepth > 0) {
                            caseDepth--;
                        }
                        // Consume the CASE keyword so it does not open a new block.
                        current.append(script, i, wordEnd);
                        current.append(script, wordEnd, after.end());
                        i = after.end();
                        continue;
                    } else if (caseDepth > 0) {
                        caseDepth--;
                    } else if (!nestedUnits.isEmpty()) {
                        NestedUnit nested = nestedUnits.peek();
                        if (nested.bodyStarted && nested.blockDepth > 0) {
                            nested.blockDepth--;
                            if (nested.blockDepth == 0) {
                                nestedUnits.pop();
                            }
                        } else {
                            nestedUnits.pop();
                        }
                    } else if (unit != null) {
                        if (unit.blockDepth > 0) {
                            unit.blockDepth--;
                            if (unit.blockDepth == 0) {
                                unit.unitEndSeen = true;
                            }
                        } else {
                            unit.unitEndSeen = true;
                        }
                    }
                }

                if (unit != null && unit.expectBodyLiteral
                        && !"AS".equals(word) && !"IS".equals(word)) {
                    unit.expectBodyLiteral = false;
                }

                current.append(script, i, wordEnd);
                i = wordEnd;
                continue;
            }

            if (c == ';') {
                current.append(c);
                i++;
                if (unit == null) {
                    if (isCopyFromStdin(current)) {
                        int dataStart = i;
                        if (dataStart < length && script.charAt(dataStart) == '\r') {
                            dataStart++;
                        }
                        if (dataStart < length && script.charAt(dataStart) == '\n') {
                            dataStart++;
                        }
                        CopyPayload payload = readCopyPayload(script, dataStart);
                        String header = current.toString().trim();
                        statements.add(Statement.copy(header.substring(0, header.length() - 1), payload.data()));
                        current.setLength(0);
                        caseDepth = 0;
                        i = payload.nextIndex();
                        continue;
                    }
                    addIfMeaningful(statements, current.toString(), false);
                    current.setLength(0);
                    caseDepth = 0;
                    continue;
                }
                if (!nestedUnits.isEmpty()) {
                    NestedUnit nested = nestedUnits.peek();
                    if (!nested.bodyStarted && nested.blockDepth == 0
                            && !nested.bodyClauseSeen) {
                        // A bare local declaration ("PROCEDURE p;") is a
                        // forward declaration, not a unit with a body. Its
                        // later "PROCEDURE p IS ..." definition pushes a new
                        // frame, so forward declarations never accumulate.
                        nestedUnits.pop();
                    }
                    continue;
                }
                boolean close = unit.unitEndSeen
                        || (!unit.bodyStarted
                                && (unit.literalBody
                                        || (unit.kind == UnitKind.TRIGGER
                                                && !unit.declarationSection)));
                if (close) {
                    // PL/SQL units keep their terminating semicolon: the
                    // Dameng/Kingbase JDBC drivers send the text verbatim and
                    // expect "END;" / "END pkg;" at the end of a block.
                    addIfMeaningful(statements, current.toString(), true);
                    current.setLength(0);
                    unit = null;
                    nestedUnits.clear();
                    caseDepth = 0;
                }
                continue;
            }

            if (c == '(') {
                parenDepth++;
            } else if (c == ')' && parenDepth > 0) {
                parenDepth--;
            }
            current.append(c);
            i++;
        }
        addIfMeaningful(statements, current.toString(), unit != null);
        return statements;
    }

    /**
     * True when a PROCEDURE/FUNCTION word declares a local subprogram rather
     * than naming one in a call. Local subprograms may themselves contain
     * declarations and further local subprograms, so the caller keeps a stack.
     */
    private static boolean inDeclarationSection(Unit unit, Deque<NestedUnit> nestedUnits) {
        if (unit == null) {
            return false;
        }
        if (!nestedUnits.isEmpty()) {
            NestedUnit nested = nestedUnits.peek();
            return !nested.bodyStarted && nested.blockDepth == 0;
        }
        if (unit.blockDepth > 0) {
            return false;
        }
        return (unit.declarationSection && !unit.bodyStarted)
                || unit.kind == UnitKind.PACKAGE
                || unit.kind == UnitKind.PACKAGE_BODY
                || unit.kind == UnitKind.TYPE_BODY;
    }

    private static void markLiteralBody(Unit unit) {
        if (unit != null && unit.expectBodyLiteral) {
            unit.literalBody = true;
            unit.expectBodyLiteral = false;
        }
    }

    /**
     * True when the words after CREATE match
     * {@code [OR REPLACE] [EDITIONABLE|NONEDITIONABLE] PROCEDURE|FUNCTION|TRIGGER|PACKAGE}
     * or {@code TYPE BODY}.
     */
    private static CreateInfo createInfo(String script, int from) {
        int position = from;
        for (int guard = 0; guard < 6; guard++) {
            WordToken token = nextSignificantWord(script, position);
            if (token == null) {
                return null;
            }
            String word = token.word();
            if (CREATE_BODY_PREFIX.contains(word)) {
                position = token.end();
                continue;
            }
            if ("PROCEDURE".equals(word)) {
                return new CreateInfo(UnitKind.PROCEDURE, token.end());
            }
            if ("FUNCTION".equals(word)) {
                return new CreateInfo(UnitKind.FUNCTION, token.end());
            }
            if ("TRIGGER".equals(word)) {
                return new CreateInfo(UnitKind.TRIGGER, token.end());
            }
            if ("PACKAGE".equals(word)) {
                WordToken afterPackage = nextSignificantWord(script, token.end());
                if (afterPackage != null && "BODY".equals(afterPackage.word())) {
                    return new CreateInfo(UnitKind.PACKAGE_BODY, token.end());
                }
                return new CreateInfo(UnitKind.PACKAGE, token.end());
            }
            if ("TYPE".equals(word)) {
                WordToken afterType = nextSignificantWord(script, token.end());
                if (afterType != null && "BODY".equals(afterType.word())) {
                    return new CreateInfo(UnitKind.TYPE_BODY, token.end());
                }
                return null;
            }
            return null;
        }
        return null;
    }

    /**
     * True for transaction control: {@code BEGIN;}, {@code BEGIN WORK;},
     * {@code BEGIN TRANSACTION [ISOLATION LEVEL ...];}, {@code BEGIN ISOLATION LEVEL ...},
     * {@code BEGIN READ ONLY;}, {@code BEGIN NOT DEFERRABLE;}.
     */
    private static boolean isTransactionBegin(String script, int from) {
        int position = skipInsignificant(script, from);
        if (position >= script.length()) {
            return false;
        }
        if (script.charAt(position) == ';') {
            return true;
        }
        WordToken token = wordAt(script, position);
        if (token == null) {
            return false;
        }
        String word = token.word();
        if ("WORK".equals(word) || "TRANSACTION".equals(word)) {
            int afterPosition = skipInsignificant(script, token.end());
            if (afterPosition < script.length() && script.charAt(afterPosition) == ';') {
                return true;
            }
            WordToken after = wordAt(script, afterPosition);
            return after != null && ("ISOLATION".equals(after.word())
                    || "READ".equals(after.word())
                    || "NOT".equals(after.word())
                    || "DEFERRABLE".equals(after.word()));
        }
        return "ISOLATION".equals(word)
                || "READ".equals(word)
                || "NOT".equals(word)
                || "DEFERRABLE".equals(word);
    }

    /** Skips whitespace and both comment styles, returning the next character index. */
    private static int skipInsignificant(String script, int from) {
        int i = from;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                i = skipLineComment(script, i);
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                i = skipBlockComment(script, i);
                continue;
            }
            return i;
        }
        return i;
    }

    private static WordToken wordAt(String script, int start) {
        if (start >= script.length() || !Character.isLetter(script.charAt(start))) {
            return null;
        }
        int i = start;
        while (i < script.length() && isIdentifierChar(script.charAt(i))) {
            i++;
        }
        return new WordToken(script.substring(start, i).toUpperCase(Locale.ROOT), start, i);
    }

    /** The next word skipping whitespace and both comment styles, or null at end of input. */
    private static WordToken nextSignificantWord(String script, int from) {
        return wordAt(script, skipInsignificant(script, from));
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Block comments nest in PostgreSQL-lineage dialects; mirror that here. */
    private static int skipBlockComment(String script, int start) {
        return skipBlockComment((CharSequence) script, start);
    }

    private static int skipBlockComment(CharSequence script, int start) {
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

    private static int skipLineComment(String script, int start) {
        int end = script.indexOf('\n', start);
        return end < 0 ? script.length() : end;
    }

    private static int skipLine(String script, int start) {
        int end = script.indexOf('\n', start);
        return end < 0 ? script.length() : end + 1;
    }

    /** Skips a quoted region; a doubled quote (e.g. '') is an escaped quote. */
    private static int skipQuoted(String script, int start, char quote, boolean backslashEscapes) {
        int i = start + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (backslashEscapes && c == '\\') {
                i += 2;
                continue;
            }
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

    /** True for the {@code E'...'} prefix (backslash escapes are enabled). */
    private static boolean isEStringPrefix(String script, int quoteIndex) {
        if (quoteIndex == 0) {
            return false;
        }
        char prefix = script.charAt(quoteIndex - 1);
        if (prefix != 'E' && prefix != 'e') {
            return false;
        }
        return quoteIndex == 1 || !isIdentifierChar(script.charAt(quoteIndex - 2));
    }

    /** True for an Oracle/Dameng {@code q'[...]'} alternative-quoted string. */
    private static boolean isQQuoteStart(String script, int i) {
        if (i + 2 >= script.length() || script.charAt(i + 1) != '\'') {
            return false;
        }
        return i == 0 || !isIdentifierChar(script.charAt(i - 1));
    }

    private static int skipQQuote(String script, int start) {
        char delimiter = script.charAt(start + 2);
        char closing = switch (delimiter) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> delimiter;
        };
        String terminator = String.valueOf(closing) + "'";
        int close = script.indexOf(terminator, start + 3);
        return close < 0 ? script.length() : close + terminator.length();
    }

    private static boolean isDollarQuoteStart(String script, int i) {
        // "$" inside an identifier (e.g. a$b$) is not a dollar quote.
        if (i > 0 && isIdentifierChar(script.charAt(i - 1))) {
            return false;
        }
        int j = i + 1;
        while (j < script.length()
                && (Character.isLetterOrDigit(script.charAt(j)) || script.charAt(j) == '_')) {
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
        return firstSignificantIndex(current) >= current.length();
    }

    /** Index of the first non-whitespace, non-comment character, or the length. */
    private static int firstSignificantIndex(CharSequence text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                int nl = indexOf(text, '\n', i);
                if (nl < 0) {
                    return text.length();
                }
                i = nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i = skipBlockComment(text, i);
                if (i >= text.length()) {
                    return text.length();
                }
                continue;
            }
            return i;
        }
        return text.length();
    }

    private static int indexOf(CharSequence text, char target, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(CharSequence text, String target, int from) {
        int max = text.length() - target.length();
        for (int i = from; i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < target.length(); j++) {
                if (text.charAt(i + j) != target.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isCopyFromStdin(CharSequence statement) {
        // Delegate to the converter's strict header parser: it accepts only
        // "COPY <table> [(columns)] FROM STDIN", not a COPY TO statement with
        // "FROM STDIN" inside a subquery or comment.
        return CopyDataConverter.isCopyFromStdin(statement.toString());
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

    private static boolean isSlashTerminator(String script, int i) {
        int lineStart = script.lastIndexOf('\n', i - 1) + 1;
        for (int j = lineStart; j < i; j++) {
            if (!Character.isWhitespace(script.charAt(j))) {
                return false;
            }
        }
        int lineEnd = script.indexOf('\n', i);
        int end = lineEnd < 0 ? script.length() : lineEnd;
        return script.substring(i, end).trim().equals("/");
    }

    private static void addIfMeaningful(
            List<Statement> statements, String raw, boolean keepTerminator) {
        String trimmed = raw.trim();
        if (!keepTerminator && trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty() || isStatementStart(new StringBuilder(trimmed))) {
            return;
        }
        statements.add(Statement.sql(trimmed));
    }
}
