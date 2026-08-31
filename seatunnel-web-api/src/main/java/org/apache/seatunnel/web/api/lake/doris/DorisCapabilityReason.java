package org.apache.seatunnel.web.api.lake.doris;

/** Stable, UI-safe reason codes for a disabled Doris capability. */
public final class DorisCapabilityReason {

    public static final String ADAPTER_MISSING = "ADAPTER_MISSING";
    public static final String DRIVER_CONFIG_MISSING = "DRIVER_CONFIG_MISSING";
    public static final String DRIVER_CHECKSUM_MISSING = "DRIVER_CHECKSUM_MISSING";
    public static final String SOURCE_CONFIG_INCOMPLETE = "SOURCE_CONFIG_INCOMPLETE";
    public static final String LAKE_DORIS_UNREACHABLE = "LAKE_DORIS_UNREACHABLE";
    public static final String SOURCE_NETWORK_UNREACHABLE = "SOURCE_NETWORK_UNREACHABLE";

    private DorisCapabilityReason() {
    }
}
