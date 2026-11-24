package org.apache.seatunnel.web.api.metadata;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.openmetadata.schema.FqnLexer;
import org.openmetadata.schema.FqnParser;

import java.util.List;

public final class OpenMetadataFqn {

    private OpenMetadataFqn() {}

    /** Preserve quoted dots and the original spelling of each FQN segment. */
    public static List<String> parts(String fqn) {
        var lexer = new FqnLexer(CharStreams.fromString(fqn));
        lexer.removeErrorListeners();
        var parser = new FqnParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.setErrorHandler(new BailErrorStrategy());
        try {
            List<String> parts = parser.fqn().name().stream().map(ParserRuleContext::getText).toList();
            return String.join(".", parts).equals(fqn) ? parts : List.of();
        } catch (ParseCancellationException error) {
            return List.of();
        }
    }

    public static String unquote(String segment) {
        return segment.startsWith("\"")
                ? segment.substring(1, segment.length() - 1).replace("\"\"", "\"") : segment;
    }
}
