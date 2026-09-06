package org.apache.seatunnel.web.api.lake.catalog;

import java.util.List;

/** Immutable capability result with stable disabled reasons. */
public record LakeCatalogCapability(
        LakeJdbcAdapterType adapter,
        boolean enabled,
        List<String> reasonCodes) {

    public LakeCatalogCapability {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSupported() {
        return enabled;
    }

    public List<String> getReasons() {
        return reasonCodes;
    }

    public List<String> getDisabledReasons() {
        return reasonCodes;
    }
}
