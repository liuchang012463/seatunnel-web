package org.apache.seatunnel.web.api.lake.doris;

import java.util.List;

/** Immutable physical/logical capability result with stable disabled reasons. */
public final class DorisCapability {

    private final boolean physicalSupported;
    private final boolean logicalSupported;
    private final List<String> reasons;

    public DorisCapability(boolean logicalSupported, List<String> reasons) {
        this(logicalSupported, logicalSupported, reasons);
    }

    public DorisCapability(boolean physicalSupported, boolean logicalSupported, List<String> reasons) {
        this.physicalSupported = physicalSupported;
        this.logicalSupported = logicalSupported;
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean isPhysicalSupported() {
        return physicalSupported;
    }

    public boolean isLogicalSupported() {
        return logicalSupported;
    }

    /** Alias used by callers that expose one capability flag. */
    public boolean isSupported() {
        return logicalSupported;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public List<String> getDisabledReasons() {
        return reasons;
    }
}
