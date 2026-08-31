package org.apache.seatunnel.web.api.lake;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validates and quotes Doris identifiers; it never quotes SQL literals. */
public final class DorisIdentifier {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private DorisIdentifier() {
    }

    public static String validate(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        String value = identifier.trim();
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Doris identifier");
        }
        return value;
    }

    public static String normalize(String identifier) {
        return validate(identifier).toLowerCase(Locale.ROOT);
    }

    public static String quote(String identifier) {
        return "`" + validate(identifier).replace("`", "``") + "`";
    }

    /** Quotes a database/table path while rejecting empty or extra path components. */
    public static String quoteQualified(String qualifiedIdentifier) {
        Objects.requireNonNull(qualifiedIdentifier, "qualifiedIdentifier");
        String[] parts = qualifiedIdentifier.trim().split("\\.", -1);
        if (parts.length < 1 || parts.length > 3) {
            throw new IllegalArgumentException("Invalid qualified Doris identifier");
        }
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) {
                result.append('.');
            }
            result.append(quote(part));
        }
        return result.toString();
    }
}
