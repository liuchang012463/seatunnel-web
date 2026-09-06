package org.apache.seatunnel.web.api.lake.catalog;

import java.util.Locale;

/** Source families supported by the logical Doris JDBC catalog foundation. */
public enum LakeJdbcAdapterType {
    MYSQL("MYSQL"),
    POSTGRESQL("POSTGRESQL"),
    ORACLE("ORACLE");

    private final String code;

    LakeJdbcAdapterType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static LakeJdbcAdapterType parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("JDBC catalog adapter must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("POSTGRE_SQL".equals(normalized) || "POSTGRES".equals(normalized)) {
            normalized = POSTGRESQL.code;
        }
        for (LakeJdbcAdapterType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported JDBC catalog adapter");
    }
}
