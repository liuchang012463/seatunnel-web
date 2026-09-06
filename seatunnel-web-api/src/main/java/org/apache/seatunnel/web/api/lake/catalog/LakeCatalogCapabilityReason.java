package org.apache.seatunnel.web.api.lake.catalog;

/** Stable, UI-safe reason codes for a logical catalog capability. */
public final class LakeCatalogCapabilityReason {

    public static final String ADAPTER_MISSING = "ADAPTER_MISSING";
    public static final String DRIVER_CONFIG_MISSING = "DRIVER_CONFIG_MISSING";
    public static final String DRIVER_CHECKSUM_MISSING = "DRIVER_CHECKSUM_MISSING";
    public static final String DRIVER_CHECKSUM_INVALID = "DRIVER_CHECKSUM_INVALID";
    /** Optional Doris catalog checksum was supplied but is not a 32-digit MD5. */
    public static final String DORIS_DRIVER_MD5_INVALID = "DORIS_DRIVER_MD5_INVALID";
    public static final String DRIVER_REGISTRY_REVISION_MISSING =
            "DRIVER_REGISTRY_REVISION_MISSING";
    public static final String DRIVER_UNAVAILABLE = "DRIVER_UNAVAILABLE";
    public static final String DRIVER_NOT_VERIFIED = "DRIVER_NOT_VERIFIED";
    public static final String SOURCE_CONFIG_INCOMPLETE = "SOURCE_CONFIG_INCOMPLETE";
    public static final String SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
    public static final String SOURCE_DISABLED = "SOURCE_DISABLED";
    public static final String SOURCE_TYPE_MISMATCH = "SOURCE_TYPE_MISMATCH";
    public static final String LAKE_DORIS_UNREACHABLE = "LAKE_DORIS_UNREACHABLE";
    public static final String SOURCE_NETWORK_UNREACHABLE = "SOURCE_NETWORK_UNREACHABLE";
    /** Source-side reachability was not probed by the capability endpoint. */
    public static final String SOURCE_NETWORK_UNKNOWN = "SOURCE_NETWORK_UNKNOWN";
    public static final String LAKE_CONTROL_PLANE_DISABLED = "LAKE_CONTROL_PLANE_DISABLED";
    /** PG and Oracle remain opt-in until their server-side driver is verified. */
    public static final String ADAPTER_DISABLED = "ADAPTER_DISABLED";
    public static final String SCOPE_UNSUPPORTED = "SCOPE_UNSUPPORTED";
    public static final String UPDATE_UNSUPPORTED = "UPDATE_UNSUPPORTED";

    private LakeCatalogCapabilityReason() {
    }
}
