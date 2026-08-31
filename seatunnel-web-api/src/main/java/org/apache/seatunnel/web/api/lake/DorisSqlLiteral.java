package org.apache.seatunnel.web.api.lake;

/** SQL literal escaping kept separate from {@link DorisIdentifier}. */
public final class DorisSqlLiteral {

    private DorisSqlLiteral() {
    }

    public static String quote(String value) {
        if (value == null) {
            return "NULL";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '\'' -> escaped.append("''");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\0' -> throw new IllegalArgumentException("NUL is not a valid Doris SQL literal");
                default -> escaped.append(character);
            }
        }
        return "'" + escaped + "'";
    }

    public static String quoteProperty(String value) {
        return quote(value);
    }
}
