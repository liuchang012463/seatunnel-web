package org.apache.seatunnel.web.api.lake.recommendation;

/** Stable, UI-safe reason identities returned by the recommendation service. */
public final class LakeRecommendationReason {

    public static final String PHYSICAL_MODE_SELECTED = "PHYSICAL_MODE_SELECTED";
    public static final String LOGICAL_MODE_SELECTED = "LOGICAL_MODE_SELECTED";
    public static final String NO_MODE_REQUESTED = "NO_MODE_REQUESTED";
    public static final String REQUEST_INCOMPLETE = "REQUEST_INCOMPLETE";
    public static final String ADAPTER_UNSUPPORTED = "ADAPTER_UNSUPPORTED";
    public static final String PHYSICAL_CAPABILITY_MISSING = "PHYSICAL_CAPABILITY_MISSING";
    public static final String PHYSICAL_CAPABILITY_UNAVAILABLE =
            "PHYSICAL_CAPABILITY_UNAVAILABLE";
    public static final String LOGICAL_CAPABILITY_MISSING = "LOGICAL_CAPABILITY_MISSING";
    public static final String LOGICAL_CAPABILITY_UNAVAILABLE =
            "LOGICAL_CAPABILITY_UNAVAILABLE";

    private LakeRecommendationReason() {
    }
}
