package org.apache.seatunnel.web.api.lake.doris;

/** Results of the six explicit checks used by the v1.4 capability decision. */
public record DorisCapabilityChecks(
        boolean adapterExists,
        boolean driverConfigExists,
        boolean driverChecksumConfigured,
        boolean sourceConfigComplete,
        boolean lakeDorisReachable,
        boolean sourceNetworkReachable) {

    public boolean allSuccessful() {
        return adapterExists && driverConfigExists && driverChecksumConfigured
                && sourceConfigComplete && lakeDorisReachable && sourceNetworkReachable;
    }
}
