package org.apache.seatunnel.web.api.lake.recommendation;

import java.util.List;

/** Safe summary of one server-owned capability used by the decision tree. */
public record LakeRecommendationCapabilitySummary(
        boolean supported,
        List<String> disabledReasons) {

    public LakeRecommendationCapabilitySummary {
        disabledReasons = disabledReasons == null ? List.of() : List.copyOf(disabledReasons);
    }

    public boolean isSupported() {
        return supported;
    }

    public List<String> getDisabledReasons() {
        return disabledReasons;
    }
}
