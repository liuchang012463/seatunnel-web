package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapability;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapabilityReason;
import org.apache.seatunnel.web.api.lake.catalog.LakeExternalCatalogCapabilityResolver;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.doris.DorisCapability;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationCapabilitySummary;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationMode;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationReason;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationRequestDTO;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationVO;
import org.apache.seatunnel.web.api.lake.recommendation.LakePhysicalCapabilityPublisher;
import org.apache.seatunnel.web.api.service.LakeRecommendationService;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
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
    private final BiFunction<Long, LakeJdbcAdapterType, DorisCapability>
            physicalCapabilitySupplier;
    private final boolean logicalReachabilityKnown;

    /**
     * Production wiring uses the server-owned publisher.  A missing or
     * unavailable target/source is represented as UNSUPPORTED rather than
     * guessed from request scope.
     */
    @Autowired
    public LakeRecommendationServiceImpl(
            LakeExternalCatalogCapabilityResolver catalogCapabilityResolver,
            LakePhysicalCapabilityPublisher physicalCapabilityPublisher) {
        this(catalogCapabilityResolver,
                (sourceDataSourceId, ignoredAdapter) ->
                        physicalCapabilityPublisher.current(sourceDataSourceId),
                false);
    }

    /** Constructor used by focused tests and a server-owned capability facade. */
    public LakeRecommendationServiceImpl(
            LakeExternalCatalogCapabilityResolver catalogCapabilityResolver,
            DorisCapability physicalCapability) {
        this(catalogCapabilityResolver, (sourceDataSourceId, adapter) -> physicalCapability, true);
    }

    public LakeRecommendationServiceImpl(
            LakeExternalCatalogCapabilityResolver catalogCapabilityResolver,
            Supplier<DorisCapability> physicalCapabilitySupplier) {
        this(catalogCapabilityResolver,
                (sourceDataSourceId, adapter) -> physicalCapabilitySupplier.get(), true);
    }

    /**
     * The production path deliberately passes {@code false}: the current
     * server has no bounded source-side reachability probe and must not let
     * the resolver's convenience overload turn configuration into a claim of
     * network reachability.  Focused tests may provide known probe outcomes.
     */
    public LakeRecommendationServiceImpl(
            LakeExternalCatalogCapabilityResolver catalogCapabilityResolver,
            BiFunction<Long, LakeJdbcAdapterType, DorisCapability> physicalCapabilitySupplier,
            boolean logicalReachabilityKnown) {
        this.catalogCapabilityResolver = Objects.requireNonNull(
                catalogCapabilityResolver, "catalogCapabilityResolver");
        this.physicalCapabilitySupplier = Objects.requireNonNull(
                physicalCapabilitySupplier, "physicalCapabilitySupplier");
        this.logicalReachabilityKnown = logicalReachabilityKnown;
    }

    @Override
    public LakeRecommendationVO recommend(LakeRecommendationRequestDTO request) {
        if (!isComplete(request)) {
            return unsupported(request, LakeRecommendationReason.REQUEST_INCOMPLETE,
                    physicalSummary(null), logicalSummary(null));
        }

        DorisCapability physicalCapability = physicalCapability(request);
        LakeCatalogCapability logicalCapability = logicalCapability(request, physicalCapability);
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

    private DorisCapability physicalCapability(LakeRecommendationRequestDTO request) {
        try {
            return physicalCapabilitySupplier.apply(
                    request.getSourceDataSourceId(), request.getAdapter());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private LakeCatalogCapability logicalCapability(
            LakeRecommendationRequestDTO request, DorisCapability physicalCapability) {
        try {
            // The physical publisher performs the bounded, server-owned Doris
            // ping. Reuse that evidence for the logical preflight instead of
            // passing the production "unknown" flag as a failed ping. The
            // source-side network is still unprobed, so it is represented by
            // SOURCE_NETWORK_UNKNOWN below.
            boolean lakeDorisReachable = logicalReachabilityKnown
                    || (physicalCapability != null
                    && physicalCapability.isPhysicalSupported());
            LakeCatalogCapability capability = catalogCapabilityResolver.resolve(
                    request.getSourceDataSourceId(), request.getAdapter(), request.getTargetScope(),
                    lakeDorisReachable, true);
            if (capability == null || logicalReachabilityKnown) {
                return capability;
            }
            List<String> reasons = appendReason(capability.reasonCodes(),
                    LakeCatalogCapabilityReason.SOURCE_NETWORK_UNKNOWN);
            return new LakeCatalogCapability(capability.adapter(), false,
                    appendReason(reasons, LakeRecommendationReason.LOGICAL_CAPABILITY_UNKNOWN));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<String> appendReason(List<String> reasons, String reason) {
        Set<String> values = new LinkedHashSet<>(safeReasons(reasons));
        values.add(reason);
        return List.copyOf(values);
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
