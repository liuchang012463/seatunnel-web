package org.apache.seatunnel.web.common.enums;

/** The role a saved data source is allowed to serve in a job. */
public enum DataSourceUsage {
    SOURCE,
    SINK;

    public static DataSourceUsage from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
