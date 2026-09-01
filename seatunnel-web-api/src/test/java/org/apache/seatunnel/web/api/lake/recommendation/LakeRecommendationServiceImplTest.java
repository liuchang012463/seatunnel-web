package org.apache.seatunnel.web.api.lake.recommendation;

import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapability;
import org.apache.seatunnel.web.api.lake.catalog.LakeExternalCatalogCapabilityResolver;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.doris.DorisCapability;
import org.apache.seatunnel.web.api.lake.doris.DorisCapabilityReason;
import org.apache.seatunnel.web.api.service.impl.LakeRecommendationServiceImpl;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LakeRecommendationServiceImplTest {

    @Test
    void recommendsPhysicalWhenMoveOrGovernanceIsRequested() {
        LakeExternalCatalogCapabilityResolver resolver = mock(
                LakeExternalCatalogCapabilityResolver.class);
        LakeRecommendationServiceImpl service = new LakeRecommendationServiceImpl(
                resolver, new DorisCapability(true, true, List.of()));

        LakeRecommendationVO result = service.recommend(request(true, false, false));

        assertEquals(LakeRecommendationMode.PHYSICAL, result.mode());
        assertEquals(LakeRecommendationReason.PHYSICAL_MODE_SELECTED, result.reason());
        assertTrue(result.physicalCapability().supported());
        assertTrue(result.disabledReasons().isEmpty());
        verify(resolver).resolve(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.TABLE);

        LakeRecommendationVO governance = service.recommend(request(false, true, false));
        assertEquals(LakeRecommendationMode.PHYSICAL, governance.mode());
    }

    @Test
    void returnsUnsupportedWithPhysicalReasonsWhenPhysicalCapabilityIsMissing() {
        LakeExternalCatalogCapabilityResolver resolver = mock(
                LakeExternalCatalogCapabilityResolver.class);
        when(resolver.resolve(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.TABLE))
                .thenReturn(new LakeCatalogCapability(LakeJdbcAdapterType.MYSQL, true, List.of()));
        LakeRecommendationServiceImpl service = new LakeRecommendationServiceImpl(resolver,
                (DorisCapability) null);

        LakeRecommendationVO result = service.recommend(request(true, false, false));

        assertEquals(LakeRecommendationMode.UNSUPPORTED, result.mode());
        assertEquals(LakeRecommendationReason.PHYSICAL_CAPABILITY_UNAVAILABLE, result.reason());
        assertTrue(result.disabledReasons().contains(
                LakeRecommendationReason.PHYSICAL_CAPABILITY_MISSING));
        assertFalse(result.physicalCapability().supported());
        assertTrue(result.logicalCapability().supported());
    }

    @Test
    void recommendsLogicalOnlyForJoinOnlyWhenCatalogCapabilityIsEnabled() {
        LakeExternalCatalogCapabilityResolver resolver = mock(
                LakeExternalCatalogCapabilityResolver.class);
        when(resolver.resolve(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.TABLE))
                .thenReturn(new LakeCatalogCapability(LakeJdbcAdapterType.MYSQL, true, List.of()));
        LakeRecommendationServiceImpl service = new LakeRecommendationServiceImpl(resolver,
                new DorisCapability(false, false, List.of(DorisCapabilityReason.LAKE_DORIS_UNREACHABLE)));

        LakeRecommendationVO result = service.recommend(request(false, false, true));

        assertEquals(LakeRecommendationMode.LOGICAL, result.mode());
        assertEquals(LakeRecommendationReason.LOGICAL_MODE_SELECTED, result.reason());
        assertTrue(result.logicalCapability().supported());
        assertTrue(result.disabledReasons().isEmpty());
    }

    @Test
    void returnsUnsupportedWithCatalogReasonsWhenJoinCapabilityIsDisabled() {
        LakeExternalCatalogCapabilityResolver resolver = mock(
                LakeExternalCatalogCapabilityResolver.class);
        when(resolver.resolve(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.TABLE))
                .thenReturn(new LakeCatalogCapability(LakeJdbcAdapterType.MYSQL, false,
                        List.of("SOURCE_NOT_FOUND")));
        LakeRecommendationServiceImpl service = new LakeRecommendationServiceImpl(resolver,
                new DorisCapability(false, false, List.of()));

        LakeRecommendationVO result = service.recommend(request(false, false, true));

        assertEquals(LakeRecommendationMode.UNSUPPORTED, result.mode());
        assertEquals(LakeRecommendationReason.LOGICAL_CAPABILITY_UNAVAILABLE, result.reason());
        assertEquals(List.of("SOURCE_NOT_FOUND"), result.logicalCapability().disabledReasons());
        assertTrue(result.disabledReasons().contains("SOURCE_NOT_FOUND"));
    }

    @Test
    void noIntentIsUnsupportedAndDoesNotTreatScopeAsAuthorization() {
        LakeExternalCatalogCapabilityResolver resolver = mock(
                LakeExternalCatalogCapabilityResolver.class);
        when(resolver.resolve(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL))
                .thenReturn(new LakeCatalogCapability(LakeJdbcAdapterType.MYSQL, true, List.of()));
        LakeRecommendationServiceImpl service = new LakeRecommendationServiceImpl(resolver,
                new DorisCapability(true, true, List.of()));
        LakeRecommendationRequestDTO request = request(false, false, false);
        request.setTargetScope(LakeCatalogScope.ALL);

        LakeRecommendationVO result = service.recommend(request);

        assertEquals(LakeRecommendationMode.UNSUPPORTED, result.mode());
        assertEquals(LakeRecommendationReason.NO_MODE_REQUESTED, result.reason());
        assertTrue(result.disabledReasons().isEmpty());
        verify(resolver).resolve(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL);
    }

    @Test
    void missingQuestionOrCapabilityProducesStableUnsupportedResult() {
        LakeExternalCatalogCapabilityResolver resolver = mock(
                LakeExternalCatalogCapabilityResolver.class);
        LakeRecommendationServiceImpl service = new LakeRecommendationServiceImpl(
                resolver, (DorisCapability) null);
        LakeRecommendationRequestDTO incomplete = request(false, false, true);
        incomplete.setTargetScope(null);

        LakeRecommendationVO result = service.recommend(incomplete);

        assertEquals(LakeRecommendationMode.UNSUPPORTED, result.mode());
        assertEquals(LakeRecommendationReason.REQUEST_INCOMPLETE, result.reason());
        assertEquals(List.of(LakeRecommendationReason.PHYSICAL_CAPABILITY_MISSING),
                result.physicalCapability().disabledReasons());
        assertEquals(List.of(LakeRecommendationReason.LOGICAL_CAPABILITY_MISSING),
                result.logicalCapability().disabledReasons());
        verifyNoInteractions(resolver);
    }

    @Test
    void resolverFailureIsReportedWithoutEscapingItsDetails() {
        LakeExternalCatalogCapabilityResolver resolver = mock(
                LakeExternalCatalogCapabilityResolver.class);
        when(resolver.resolve(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.TABLE))
                .thenThrow(new IllegalStateException("jdbc password=secret"));
        LakeRecommendationServiceImpl service = new LakeRecommendationServiceImpl(resolver,
                new DorisCapability(false, false, List.of()));

        LakeRecommendationVO result = service.recommend(request(false, false, true));

        assertEquals(LakeRecommendationMode.UNSUPPORTED, result.mode());
        assertEquals(LakeRecommendationReason.LOGICAL_CAPABILITY_UNAVAILABLE, result.reason());
        assertFalse(result.toString().contains("secret"));
        assertTrue(result.logicalCapability().disabledReasons().contains(
                LakeRecommendationReason.LOGICAL_CAPABILITY_MISSING));
    }

    private static LakeRecommendationRequestDTO request(
            boolean moveData, boolean physicalGovernance, boolean joinOnly) {
        LakeRecommendationRequestDTO request = new LakeRecommendationRequestDTO();
        request.setMoveData(moveData);
        request.setPhysicalGovernance(physicalGovernance);
        request.setJoinOnly(joinOnly);
        request.setTargetScope(LakeCatalogScope.TABLE);
        request.setSourceDataSourceId(7L);
        request.setAdapter(LakeJdbcAdapterType.MYSQL);
        return request;
    }
}
