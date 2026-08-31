package org.apache.seatunnel.web.api.lake.doris;

import java.util.ArrayList;
import java.util.List;

/** Applies the v1.4 capability decision tree without guessing missing checks. */
public final class DorisCapabilityResolver {

    public DorisCapability resolve(DorisCapabilityChecks checks) {
        if (checks == null) {
            return new DorisCapability(false, false, List.of(DorisCapabilityReason.SOURCE_CONFIG_INCOMPLETE));
        }
        List<String> reasons = new ArrayList<>(6);
        if (!checks.adapterExists()) {
            reasons.add(DorisCapabilityReason.ADAPTER_MISSING);
        }
        if (!checks.driverConfigExists()) {
            reasons.add(DorisCapabilityReason.DRIVER_CONFIG_MISSING);
        }
        if (!checks.driverChecksumConfigured()) {
            reasons.add(DorisCapabilityReason.DRIVER_CHECKSUM_MISSING);
        }
        if (!checks.sourceConfigComplete()) {
            reasons.add(DorisCapabilityReason.SOURCE_CONFIG_INCOMPLETE);
        }
        if (!checks.lakeDorisReachable()) {
            reasons.add(DorisCapabilityReason.LAKE_DORIS_UNREACHABLE);
        }
        if (!checks.sourceNetworkReachable()) {
            reasons.add(DorisCapabilityReason.SOURCE_NETWORK_UNREACHABLE);
        }
        boolean physical = checks.lakeDorisReachable();
        return new DorisCapability(physical, reasons.isEmpty(), reasons);
    }

    public DorisCapability resolve(boolean adapterExists, boolean driverConfigExists,
                                   boolean driverChecksumConfigured, boolean sourceConfigComplete,
                                   boolean lakeDorisReachable, boolean sourceNetworkReachable) {
        return resolve(new DorisCapabilityChecks(adapterExists, driverConfigExists,
                driverChecksumConfigured, sourceConfigComplete, lakeDorisReachable,
                sourceNetworkReachable));
    }

    public static DorisCapability evaluate(DorisCapabilityChecks checks) {
        return new DorisCapabilityResolver().resolve(checks);
    }
}
