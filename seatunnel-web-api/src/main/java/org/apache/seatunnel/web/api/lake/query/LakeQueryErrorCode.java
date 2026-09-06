package org.apache.seatunnel.web.api.lake.query;

/** Stable, secret-free error identities for bounded query execution. */
public final class LakeQueryErrorCode {

    public static final String CONFIG_INVALID = "LAKE_QUERY_CONFIG_INVALID";
    public static final String DATASOURCE_UNAVAILABLE = "LAKE_QUERY_DATASOURCE_UNAVAILABLE";
    public static final String READONLY_REJECTED = "LAKE_QUERY_READONLY_REJECTED";
    public static final String TIMEOUT = "LAKE_QUERY_TIMEOUT";
    public static final String CANCELLED = "LAKE_QUERY_CANCELLED";
    public static final String EXECUTION_FAILED = "LAKE_QUERY_EXECUTION_FAILED";
    public static final String RESULT_LIMIT_INVALID = "LAKE_QUERY_RESULT_LIMIT_INVALID";
    public static final String RESULT_BYTES_INVALID = "LAKE_QUERY_RESULT_BYTES_INVALID";

    private LakeQueryErrorCode() {
    }
}
