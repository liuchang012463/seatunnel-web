package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleBindingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleConfirmationTokenService;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleMappingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleRetentionPreviewVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleValidationService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionPreviewDTO;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeLifecycleRetentionPreviewServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock private LakeLifecycleValidationService validationService;
    @Mock private LakeTableLifecycleBindingDao lifecycleBindingDao;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private LakeLifecycleConfirmationTokenService tokenService;

    private LakeLifecycleRetentionPreviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LakeLifecycleRetentionPreviewServiceImpl(
                validationService, lifecycleBindingDao, currentUserProvider, tokenService);
        when(currentUserProvider.getCurrentUserId()).thenReturn(7);
    }

    @Test
    void decreaseKeepsNewestHistoricalPartitionsAndIssuesSignedToken() {
        LakeLifecycleValidateVO validated = validResult(2, bindingSnapshot(5));
        when(validationService.validate(any())).thenReturn(validated);
        when(tokenService.issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(), anyInt(),
                anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenReturn("signed-confirmation-token");

        LakeLifecycleRetentionPreviewVO result = service.preview(501L, request(901L));

        assertTrue(result.isValid());
        assertTrue(result.isRequiresConfirmation());
        assertEquals(3, result.getHistoricalPartitionCount());
        assertEquals(List.of("p_old"), result.getImpactedHistoricalPartitionNames());
        assertEquals(1, result.getImpactedHistoricalPartitionCount());
        assertEquals("signed-confirmation-token", result.getConfirmationToken());
        verify(tokenService).issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(), anyInt(),
                anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void punctuationAndNewlinesInPartitionNamesHaveAnUnambiguousImpactHash() {
        LakeLifecycleValidateVO validated = validResult(2, bindingSnapshot(5));
        DorisPartitionSummary summary = new DorisPartitionSummary(
                4, 3, 1, 0, 0,
                List.of("p:old\n[0]", "p|mid", "p,latest", "p_current"), NOW,
                List.of("p:old\n[0]", "p|mid", "p,latest"),
                List.of("p_current"), List.of(), List.of());
        validated.setPartitionSummary(summary);
        when(validationService.validate(any())).thenReturn(validated);
        when(tokenService.issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(), anyInt(),
                anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), anyString()))
                .thenReturn("signed-confirmation-token");

        LakeLifecycleRetentionPreviewVO result = service.preview(501L, request(901L));

        assertTrue(result.isValid());
        assertEquals(List.of("p:old\n[0]"), result.getImpactedHistoricalPartitionNames());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(tokenService).issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(), anyInt(),
                anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), hash.capture());
        assertEquals(LakeLifecycleRetentionPreviewServiceImpl.observedImpactHash(
                validated.getMappingSnapshot(), validated.getExistingBinding(), validated, 2,
                List.of("p:old\n[0]")), hash.getValue());
        assertEquals(64, hash.getValue().length());
    }

    @Test
    void increaseDoesNotNeedConfirmationToken() {
        LakeLifecycleValidateVO validated = validResult(7, bindingSnapshot(5));
        when(validationService.validate(any())).thenReturn(validated);

        LakeLifecycleRetentionPreviewVO result = service.preview(501L, request(901L));

        assertTrue(result.isValid());
        assertFalse(result.isRequiresConfirmation());
        assertEquals(0, result.getImpactedHistoricalPartitionCount());
        assertNull(result.getConfirmationToken());
        verify(tokenService, never()).issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void unknownPartitionObservationCannotIssueDecreaseConfirmation() {
        LakeLifecycleValidateVO validated = validResult(2, bindingSnapshot(5));
        validated.setPartitionSummary(new DorisPartitionSummary(
                4, 2, 1, 0, 1, List.of("p_old", "p_unknown"), NOW,
                List.of("p_old"), List.of("p_current"), List.of(), List.of("p_unknown")));
        when(validationService.validate(any())).thenReturn(validated);

        LakeLifecycleRetentionPreviewVO result = service.preview(501L, request(901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleRetentionPreviewServiceImpl.IMPACT_OBSERVATION_UNKNOWN,
                result.getCode());
        assertFalse(result.isRequiresConfirmation());
        verify(tokenService, never()).issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void noExistingBindingHasNoDecreaseToConfirm() {
        LakeLifecycleValidateVO validated = validResult(2, null);
        validated.setExistingBinding(null);
        when(validationService.validate(any())).thenReturn(validated);

        LakeLifecycleRetentionPreviewVO result = service.preview(501L, request(901L));

        assertTrue(result.isValid());
        assertFalse(result.isRequiresConfirmation());
        assertNull(result.getCurrentDesiredRetentionCount());
        verify(tokenService, never()).issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void pendingBindingIsRejectedBeforeIssuingToken() {
        LakeLifecycleValidateVO validated = validResult(2, bindingSnapshot(5));
        LakeTableLifecycleBinding pending = new LakeTableLifecycleBinding();
        pending.setId(701L);
        pending.setTableMappingId(501L);
        pending.setStatus(LakeLifecycleBindingStatus.PENDING);
        when(validationService.validate(any())).thenReturn(validated);
        when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(pending);

        LakeLifecycleRetentionPreviewVO result = service.preview(501L, request(901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleRetentionPreviewServiceImpl.BINDING_NOT_ACTIVE,
                result.getCode());
        verify(tokenService, never()).issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void validationFailureIsReturnedWithoutAttemptingToken() {
        LakeLifecycleValidateVO validated = new LakeLifecycleValidateVO();
        validated.setMappingId(501L);
        validated.setPolicyId(901L);
        validated.setCode("LAKE_LIFECYCLE_STRUCTURAL_DRIFT");
        validated.setReasons(List.of("LAKE_LIFECYCLE_STRUCTURAL_DRIFT"));
        validated.setValid(false);
        when(validationService.validate(any())).thenReturn(validated);

        LakeLifecycleRetentionPreviewVO result = service.preview(501L, request(901L));

        assertFalse(result.isValid());
        assertEquals("LAKE_LIFECYCLE_STRUCTURAL_DRIFT", result.getCode());
        verify(lifecycleBindingDao, never()).queryByTableMappingId(anyLong());
        verify(tokenService, never()).issue(anyInt(), anyLong(), anyInt(), anyInt(), anyLong(),
                anyInt(), anyInt(), anyInt(), anyLong(), anyInt(), anyInt(), anyString());
    }

    private static LakeLifecycleValidateVO validResult(
            int requestedRetention, LakeLifecycleBindingSnapshotVO binding) {
        LakeLifecycleValidateVO result = new LakeLifecycleValidateVO();
        result.setValid(true);
        result.setCode("LAKE_LIFECYCLE_VALID");
        result.setMappingId(501L);
        result.setPolicyId(901L);
        result.setDesiredRetentionCount(requestedRetention);
        result.setMappingSnapshot(mappingSnapshot());
        result.setPolicySnapshot(policySnapshot(requestedRetention));
        result.setExistingBinding(binding);
        result.setPartitionSummary(new DorisPartitionSummary(
                4, 3, 1, 0, 0, List.of("p_old", "p_mid", "p_new", "p_current"), NOW,
                List.of("p_old", "p_mid", "p_new"), List.of("p_current"), List.of(), List.of()));
        result.setObservedAt(NOW);
        return result;
    }

    private static LakeLifecycleMappingSnapshotVO mappingSnapshot() {
        LakeLifecycleMappingSnapshotVO mapping = new LakeLifecycleMappingSnapshotVO();
        mapping.setId(501L);
        mapping.setGeneration(3);
        mapping.setLockVersion(4);
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setTargetConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        return mapping;
    }

    private static LakeLifecyclePolicyVO policySnapshot(int retention) {
        LakeLifecyclePolicyVO policy = new LakeLifecyclePolicyVO();
        policy.setId(901L);
        policy.setVersion(2);
        policy.setStatus(LakeLifecyclePolicyStatus.ACTIVE);
        policy.setGranularity(LakePartitionGranularity.DAY);
        policy.setRetentionCount(retention);
        return policy;
    }

    private static LakeLifecycleBindingSnapshotVO bindingSnapshot(int retention) {
        LakeLifecycleBindingSnapshotVO binding = new LakeLifecycleBindingSnapshotVO();
        binding.setId(701L);
        binding.setTableMappingId(501L);
        binding.setPolicyId(901L);
        binding.setPolicyVersion(2);
        binding.setGranularity(LakePartitionGranularity.DAY);
        binding.setRetentionCount(retention);
        binding.setActualRetentionCount(retention);
        binding.setGeneration(1);
        binding.setLockVersion(2);
        binding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        return binding;
    }

    private static LakeLifecycleRetentionPreviewDTO request(Long policyId) {
        LakeLifecycleRetentionPreviewDTO request = new LakeLifecycleRetentionPreviewDTO();
        request.setPolicyId(policyId);
        return request;
    }
}
