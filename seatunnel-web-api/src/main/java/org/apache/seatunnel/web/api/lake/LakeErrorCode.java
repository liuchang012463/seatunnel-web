package org.apache.seatunnel.web.api.lake;

/** Stable error identities exposed by the v1.4 lake control plane. */
public final class LakeErrorCode {

    public static final String LAKE_MASTER_DATA_INCOMPLETE = "LAKE_MASTER_DATA_INCOMPLETE";
    public static final String LAKE_MASTER_DATA_CODE_INVALID = "LAKE_MASTER_DATA_CODE_INVALID";
    public static final String LAKE_SOURCE_OBJECT_MISSING = "LAKE_SOURCE_OBJECT_MISSING";
    public static final String LAKE_SOURCE_OBJECT_UNKNOWN = "LAKE_SOURCE_OBJECT_UNKNOWN";
    public static final String LAKE_DATABASE_NAME_CONFLICT = "LAKE_DATABASE_NAME_CONFLICT";
    public static final String LAKE_DATABASE_MISSING = "LAKE_DATABASE_MISSING";
    public static final String LAKE_DATABASE_IN_USE = "LAKE_DATABASE_IN_USE";
    public static final String LAKE_RESOURCE_CONFLICT = "LAKE_RESOURCE_CONFLICT";
    public static final String LAKE_OPERATION_STALE = "LAKE_OPERATION_STALE";
    public static final String LAKE_DORIS_UNAVAILABLE = "LAKE_DORIS_UNAVAILABLE";
    public static final String LAKE_REQUEST_INVALID = "LAKE_REQUEST_INVALID";
    public static final String LAKE_LIFECYCLE_REQUIRES_PREPARTITIONED_TABLE =
            "LAKE_LIFECYCLE_REQUIRES_PREPARTITIONED_TABLE";

    private LakeErrorCode() {
    }

    /** Numeric values are stable for the existing Result envelope. */
    public static int httpCode(String code) {
        return switch (code) {
            case LAKE_MASTER_DATA_INCOMPLETE -> 11901;
            case LAKE_MASTER_DATA_CODE_INVALID -> 11902;
            case LAKE_SOURCE_OBJECT_MISSING -> 11903;
            case LAKE_SOURCE_OBJECT_UNKNOWN -> 11904;
            case LAKE_DATABASE_NAME_CONFLICT -> 11905;
            case LAKE_DATABASE_MISSING -> 11906;
            case LAKE_DATABASE_IN_USE -> 11907;
            case LAKE_RESOURCE_CONFLICT -> 11908;
            case LAKE_OPERATION_STALE -> 11909;
            case LAKE_DORIS_UNAVAILABLE -> 11910;
            case LAKE_REQUEST_INVALID -> 11911;
            case LAKE_LIFECYCLE_REQUIRES_PREPARTITIONED_TABLE -> 11912;
            default -> 11900;
        };
    }
}
