package org.apache.seatunnel.web.api.lake.query;

import java.util.Objects;
import java.util.regex.Pattern;

/** Conservative identifier validation used by every query path. */
public final class LakeQueryIdentifier {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private LakeQueryIdentifier() {
    }

    public static String validate(String value) {
        Objects.requireNonNull(value, "identifier");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid query identifier");
        }
        return value;
    }

    public static String quote(String value) {
        return "`" + validate(value) + "`";
    }
}
