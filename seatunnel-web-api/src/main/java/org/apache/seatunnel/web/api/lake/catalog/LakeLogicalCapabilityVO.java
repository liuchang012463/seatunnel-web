package org.apache.seatunnel.web.api.lake.catalog;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

import java.util.List;

/** Secret-free capability response for a source-backed logical catalog. */
@Data
public class LakeLogicalCapabilityVO {

    private Long sourceDataSourceId;

    private LakeJdbcAdapterType adapter;

    private LakeCatalogScope scope;

    private boolean logicalSupported;

    /** Alias kept for clients that use the generic capability vocabulary. */
    private boolean supported;

    /** True only when the source-side reachability check has actually run. */
    private boolean sourceNetworkReachabilityKnown;

    private boolean lakeDorisReachable;

    private List<String> reasonCodes = List.of();

    public LakeLogicalCapabilityVO() {
    }

    public LakeLogicalCapabilityVO(
            Long sourceDataSourceId,
            LakeJdbcAdapterType adapter,
            LakeCatalogScope scope,
            boolean logicalSupported,
            boolean sourceNetworkReachabilityKnown,
            boolean lakeDorisReachable,
            List<String> reasonCodes) {
        this.sourceDataSourceId = sourceDataSourceId;
        this.adapter = adapter;
        this.scope = scope;
        this.logicalSupported = logicalSupported;
        this.supported = logicalSupported;
        this.sourceNetworkReachabilityKnown = sourceNetworkReachabilityKnown;
        this.lakeDorisReachable = lakeDorisReachable;
        this.reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public boolean isLogicalSupported() {
        return logicalSupported;
    }

    public boolean isSupported() {
        return supported;
    }

    public List<String> getReasons() {
        return reasonCodes;
    }
}
