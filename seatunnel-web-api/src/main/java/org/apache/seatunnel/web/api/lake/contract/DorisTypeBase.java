package org.apache.seatunnel.web.api.lake.contract;

import java.util.Locale;

/**
 * The deliberately small type vocabulary used by the v2 target contract.
 *
 * <p>The contract describes types that can be rendered and compared by the
 * Web control plane.  Doris-specific aliases are normalised at the boundary
 * (for example, {@code TEXT} is represented as {@code STRING}).</p>
 */
public enum DorisTypeBase {
    BOOLEAN,
    TINYINT,
    SMALLINT,
    INT,
    BIGINT,
    LARGEINT,
    FLOAT,
    DOUBLE,
    DECIMAL,
    DATE,
    DATETIME,
    CHAR,
    VARCHAR,
    STRING,
    TEXT,
    JSON,
    ARRAY,
    MAP,
    STRUCT;

    public static DorisTypeBase parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Target type base must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("DECIMAL")) {
            return DECIMAL;
        }
        return switch (normalized) {
            case "BOOL" -> BOOLEAN;
            case "INTEGER" -> INT;
            case "TEXT" -> TEXT;
            default -> {
                try {
                    yield valueOf(normalized);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Unsupported Doris target type", exception);
                }
            }
        };
    }

    /** Returns the v2 structural spelling used in canonical JSON. */
    public DorisTypeBase canonical() {
        return this == TEXT ? STRING : this;
    }

    public boolean isComplex() {
        return this == ARRAY || this == MAP || this == STRUCT;
    }

    public boolean isKeyForbidden() {
        return canonical() == STRING || this == FLOAT || this == DOUBLE || isComplex();
    }
}
