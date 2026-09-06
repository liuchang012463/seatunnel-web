package org.apache.seatunnel.web.api.lake.recommendation;

import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;

import java.util.List;
import java.util.Objects;

/** Read-only recommendation and the capability evidence behind it. */
public record LakeRecommendationVO(
        LakeRecommendationMode mode,
        String reason,
        List<String> disabledReasons,
        LakeRecommendationCapabilitySummary physicalCapability,
        LakeRecommendationCapabilitySummary logicalCapability,
        LakeCatalogScope targetScope,
        LakeJdbcAdapterType adapter) {

    public LakeRecommendationVO {
        mode = Objects.requireNonNull(mode, "mode");
        reason = Objects.requireNonNull(reason, "reason");
        disabledReasons = disabledReasons == null ? List.of() : List.copyOf(disabledReasons);
        physicalCapability = Objects.requireNonNull(physicalCapability, "physicalCapability");
        logicalCapability = Objects.requireNonNull(logicalCapability, "logicalCapability");
    }

    public LakeRecommendationMode recommendation() {
        return mode;
    }

    public LakeRecommendationMode getRecommendation() {
        return mode;
    }

    public String reasonCode() {
        return reason;
    }

    public String getReasonCode() {
        return reason;
    }

    public List<String> getDisabledReasons() {
        return disabledReasons;
    }
}
