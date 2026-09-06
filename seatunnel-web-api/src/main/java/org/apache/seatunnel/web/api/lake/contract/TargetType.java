package org.apache.seatunnel.web.api.lake.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.Objects;

/** A Doris type and its optional scalar parameters in TargetContract v2. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"base", "length", "precision", "scale"})
public class TargetType {

    private DorisTypeBase base;
    private Integer length;
    private Integer precision;
    private Integer scale;

    public TargetType(DorisTypeBase base) {
        this.base = base;
    }

    public TargetType(String base) {
        this(DorisTypeBase.parse(base));
    }

    public static TargetType varchar(int length) {
        return new TargetType(DorisTypeBase.VARCHAR, length, null, null);
    }

    public static TargetType decimal(int precision, int scale) {
        return new TargetType(DorisTypeBase.DECIMAL, null, precision, scale);
    }

    public TargetType canonicalCopy() {
        validate();
        DorisTypeBase canonicalBase = Objects.requireNonNull(base, "base").canonical();
        Integer canonicalScale = canonicalBase == DorisTypeBase.DATETIME && Objects.equals(scale, 0)
                ? null : scale;
        return new TargetType(canonicalBase, length, precision, canonicalScale);
    }

    public void validate() {
        if (base == null) {
            throw new IllegalArgumentException("Target type base must be specified");
        }
        if (length != null && length <= 0) {
            throw new IllegalArgumentException("Target type length must be positive");
        }
        DorisTypeBase canonicalBase = base.canonical();
        if ((canonicalBase == DorisTypeBase.VARCHAR || canonicalBase == DorisTypeBase.CHAR)
                && (length == null || length > 1_048_576)) {
            throw new IllegalArgumentException("VARCHAR/CHAR length must be between 1 and 1048576");
        }
        if (canonicalBase != DorisTypeBase.VARCHAR && canonicalBase != DorisTypeBase.CHAR
                && length != null) {
            throw new IllegalArgumentException("Length is only valid for VARCHAR or CHAR");
        }
        if (canonicalBase == DorisTypeBase.DECIMAL) {
            if (precision == null || scale == null || precision < 1 || precision > 38
                    || scale < 0 || scale > precision) {
                throw new IllegalArgumentException("DECIMAL precision and scale are invalid");
            }
        } else if (canonicalBase == DorisTypeBase.DATETIME) {
            if (precision != null) {
                throw new IllegalArgumentException("Precision is only valid for DECIMAL");
            }
            if (scale != null && (scale < 0 || scale > 6)) {
                throw new IllegalArgumentException("DATETIME scale must be between 0 and 6");
            }
        } else if (precision != null || scale != null) {
            throw new IllegalArgumentException("Precision and scale are only valid for DECIMAL");
        }
        if (canonicalBase != DorisTypeBase.DATETIME && scale != null
                && canonicalBase != DorisTypeBase.DECIMAL) {
            throw new IllegalArgumentException("Scale is not valid for this target type");
        }
    }

    /** Parses a type token from SHOW CREATE TABLE. */
    public static TargetType parseDorisType(String typeToken) {
        if (typeToken == null || typeToken.isBlank()) {
            throw new IllegalArgumentException("Doris column type must not be blank");
        }
        String token = typeToken.trim();
        int open = token.indexOf('(');
        String name = open < 0 ? token : token.substring(0, open).trim();
        String arguments = null;
        if (open >= 0) {
            if (!token.endsWith(")")) {
                throw new IllegalArgumentException("Malformed Doris column type");
            }
            arguments = token.substring(open + 1, token.length() - 1).trim();
        }
        DorisTypeBase parsed = DorisTypeBase.parse(name);
        if (parsed == DorisTypeBase.DECIMAL) {
            if (arguments == null || arguments.isBlank()) {
                // Doris prints DECIMAL without parameters for its default
                // precision.  Make it explicit in the structural contract.
                return decimal(9, 0);
            }
            String[] values = arguments.split(",", -1);
            if (values.length != 2) {
                throw new IllegalArgumentException("DECIMAL requires precision and scale");
            }
            return decimal(parseInteger(values[0]), parseInteger(values[1]));
        }
        if (parsed == DorisTypeBase.VARCHAR || parsed == DorisTypeBase.CHAR) {
            if (arguments == null || arguments.isBlank()) {
                throw new IllegalArgumentException("VARCHAR/CHAR length is missing");
            }
            return new TargetType(parsed, parseInteger(arguments), null, null);
        }
        if (parsed == DorisTypeBase.DATETIME && arguments != null && !arguments.isBlank()) {
            return new TargetType(parsed, null, null, parseInteger(arguments));
        }
        if (arguments != null && !arguments.isBlank()) {
            // Nested complex type syntax is intentionally not guessed.  A
            // reader must not silently turn an unsupported structural type
            // into a different type.
            throw new IllegalArgumentException("Unsupported Doris type parameters");
        }
        return new TargetType(parsed);
    }

    private static int parseInteger(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Malformed Doris type number", exception);
        }
    }

    public String canonicalBaseName() {
        return canonicalCopy().getBase().name().toUpperCase(Locale.ROOT);
    }
}
