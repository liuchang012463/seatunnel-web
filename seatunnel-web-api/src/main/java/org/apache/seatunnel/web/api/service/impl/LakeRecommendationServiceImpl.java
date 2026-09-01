package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapability;
import org.apache.seatunnel.web.api.lake.catalog.LakeExternalCatalogCapabilityResolver;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.doris.DorisCapability;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationCapabilitySummary;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationMode;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationReason;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationRequestDTO;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationVO;
import org.apache.seatunnel.web.api.service.LakeRecommendationService;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Applies the v1.4 recommendation tree without probing or mutating an
 * external system.  Physical capability is supplied by the server-owned
 * capability publisher; logical capability is resolved from the existing
 * catalog resolver for the requested source and scope.
 */
@Service
public class LakeRecommendationServiceImpl implements LakeRecommendationService {

    private final LakeExternalCatalogCapabilityResolver catalogCapabilityResolver;
    private final Supplier<DorisCapability> physicalCapabilitySupplier;

    /**
     * Optional capability publication keeps this endpoint safe while the
     * physical capability provider is not configured.  Missing capability is
     * represented as UNSUPPORTED rather than guessed from request scope.
     */
    @Autowired
    public LakeRecommendationServiceImpl(
            LakeExternalCatalogCapabilityResolver catalogCapabilityResolver,
            ObjectProvider<DorisCapability> physicalCapabilityProvider) {
        this(catalogCapabilityResolver,
                physicalCapabilityProvider == null
                        ? () -> null
                        : physicalCapabilityProvider::getIfAvailable);
    }

    /** Constructor used by focused tests and a server-owned capability facade. */
    public LakeRecommendationServiceImpl(
            LakeExternalCatalogCapabilityResolver catalogCapabilityResolver,
            DorisCapability physicalCapability) {
        this(catalogCapabilityResolver, () -> physicalCapability);
    }

    public LakeRecommendationServiceImpl(
            LakeExternalCatalogCapabilityResolver catalogCapabilityResolver,
            Supplier<DorisCapability> physicalCapabilitySupplier) {
        this.catalogCapabilityResolver = Objects.requireNonNull(
                catalogCapabilityResolver, "catalogCapabilityResolver");
        this.physicalCapabilitySupplier = Objects.requireNonNull(
                physicalCapabilitySupplier, "physicalCapabilitySupplier");
    }

    @Override
    public LakeRecommendationVO recommend(LakeRecommendationRequestDTO request) {
        if (!isComplete(request)) {
            return unsupported(request, LakeRecommendationReason.REQUEST_INCOMPLETE,
                    physicalSummary(null), logicalSummary(null));
        }

        DorisCapability physicalCapability = physicalCapability();
        LakeCatalogCapability logicalCapability = logicalCapability(request);
        LakeRecommendationCapabilitySummary physical = physicalSummary(physicalCapability);
        LakeRecommendationCapabilitySummary logical = logicalSummary(logicalCapability);

        if (Boolean.TRUE.equals(request.getMoveData())
                || Boolean.TRUE.equals(request.getPhysicalGovernance())) {
            if (physical.supported()) {
                return result(request, LakeRecommendationMode.PHYSICAL,
                        LakeRecommendationReason.PHYSICAL_MODE_SELECTED, List.of(), physical, logical);
            }
            return unsupported(request, LakeRecommendationReason.PHYSICAL_CAPABILITY_UNAVAILABLE,
                    physical, logical);
        }
        if (Boolean.TRUE.equals(request.getJoinOnly())) {
            if (logical.supported()) {
                return result(request, LakeRecommendationMode.LOGICAL,
                        LakeRecommendationReason.LOGICAL_MODE_SELECTED, List.of(), physical, logical);
            }
            return unsupported(request, LakeRecommendationReason.LOGICAL_CAPABILITY_UNAVAILABLE,
                    physical, logical);
        }
        return unsupported(request, LakeRecommendationReason.NO_MODE_REQUESTED, physical, logical);
    }

    private DorisCapability physicalCapability() {
        try {
            return physicalCapabilitySupplier.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private LakeCatalogCapability logicalCapability(LakeRecommendationRequestDTO request) {
        try {
            return catalogCapabilityResolver.resolve(
                    request.getSourceDataSourceId(), request.getAdapter(), request.getTargetScope());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isComplete(LakeRecommendationRequestDTO request) {
        return request != null
                && request.getMoveData() != null
                && request.getPhysicalGovernance() != null
                && request.getJoinOnly() != null
                && request.getTargetScope() != null
                && request.getSourceDataSourceId() != null
                && request.getSourceDataSourceId() > 0
                && request.getAdapter() != null;
    }

    private static LakeRecommendationCapabilitySummary physicalSummary(
            DorisCapability capability) {
        if (capability == null) {
            return new LakeRecommendationCapabilitySummary(false,
                    List.of(LakeRecommendationReason.PHYSICAL_CAPABILITY_MISSING));
        }
        List<String> reasons = safeReasons(capability.getDisabledReasons());
        if (!capability.isPhysicalSupported() && reasons.isEmpty()) {
            reasons = List.of(LakeRecommendationReason.PHYSICAL_CAPABILITY_UNAVAILABLE);
        }
        return new LakeRecommendationCapabilitySummary(capability.isPhysicalSupported(), reasons);
    }

    private static LakeRecommendationCapabilitySummary logicalSummary(
            LakeCatalogCapability capability) {
        if (capability == null) {
            return new LakeRecommendationCapabilitySummary(false,
                    List.of(LakeRecommendationReason.LOGICAL_CAPABILITY_MISSING));
        }
        List<String> reasons = safeReasons(capability.reasonCodes());
        if (!capability.enabled() && reasons.isEmpty()) {
            reasons = List.of(LakeRecommendationReason.LOGICAL_CAPABILITY_UNAVAILABLE);
        }
        return new LakeRecommendationCapabilitySummary(capability.enabled(), reasons);
    }

    private static LakeRecommendationVO result(
            LakeRecommendationRequestDTO request,
            LakeRecommendationMode mode,
            String reason,
            List<String> disabledReasons,
            LakeRecommendationCapabilitySummary physical,
            LakeRecommendationCapabilitySummary logical) {
        return new LakeRecommendationVO(mode, reason, disabledReasons, physical, logical,
                request.getTargetScope(), request.getAdapter());
    }

    private static LakeRecommendationVO unsupported(
            LakeRecommendationRequestDTO request,
            String reason,
            LakeRecommendationCapabilitySummary physical,
            LakeRecommendationCapabilitySummary logical) {
        LakeCatalogScope scope = request == null ? null : request.getTargetScope();
        LakeJdbcAdapterType adapter = request == null ? null : request.getAdapter();
        return new LakeRecommendationVO(LakeRecommendationMode.UNSUPPORTED, reason,
                unionReasons(physical, logical), physical, logical, scope, adapter);
    }

    private static List<String> unionReasons(
            LakeRecommendationCapabilitySummary physical,
            LakeRecommendationCapabilitySummary logical) {
        Set<String> reasons = new LinkedHashSet<>();
        if (physical != null) {
            reasons.addAll(physical.disabledReasons());
        }
        if (logical != null) {
            reasons.addAll(logical.disabledReasons());
        }
        return List.copyOf(reasons);
    }

    private static List<String> safeReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        Set<String> safe = new LinkedHashSet<>();
        for (String reason : reasons) {
            if (reason != null && !reason.isBlank()) {
                safe.add(reason);
            }
        }
        return new ArrayList<>(safe);
    }
}
