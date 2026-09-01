package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyDisableDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LakeLifecyclePolicyServiceImplTest {

    @Mock private LakeLifecyclePolicyDao policyDao;
    @Mock private CurrentUserProvider currentUserProvider;

    private LakeLifecyclePolicyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LakeLifecyclePolicyServiceImpl(policyDao, currentUserProvider);
        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(7);
        lenient().when(policyDao.insert(any())).thenReturn(1);
    }

    @Test
    void createTrimsNameInitializesVersionAndAuditFields() {
        LakeLifecyclePolicyCreateDTO request = createRequest("  daily  ");
        when(policyDao.queryByPolicyName("daily")).thenReturn(null);

        LakeLifecyclePolicyVO result = service.create(request);

        assertEquals("daily", result.getPolicyName());
        assertEquals(1, result.getVersion());
        assertEquals(LakeLifecyclePolicyStatus.DRAFT, result.getStatus());
        assertEquals(7, result.getCreateUserId());
        assertEquals(7, result.getUpdateUserId());
        ArgumentCaptor<LakeLifecyclePolicy> captor = ArgumentCaptor.forClass(LakeLifecyclePolicy.class);
        verify(policyDao).insert(captor.capture());
        assertEquals(1, captor.getValue().getVersion());
        assertEquals("daily", captor.getValue().getPolicyName());
    }

    @Test
    void createRejectsInvalidDefinitionAndDisabledStatus() {
        LakeLifecyclePolicyCreateDTO invalidRequest = createRequest(" ");
        invalidRequest.setRetentionCount(0);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.create(invalidRequest));

        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
        verify(policyDao, never()).insert(any());

        LakeLifecyclePolicyCreateDTO disabledRequest = createRequest("daily");
        disabledRequest.setStatus(LakeLifecyclePolicyStatus.DISABLED);
        exception = assertThrows(
                LakeServiceException.class, () -> service.create(disabledRequest));
        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
        verify(policyDao, never()).insert(any());
    }

    @Test
    void createMapsUniqueNameToStableConflict() {
        when(policyDao.queryByPolicyName("daily")).thenReturn(policy(11L, 1,
                LakeLifecyclePolicyStatus.DRAFT));

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.create(createRequest("daily")));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(policyDao, never()).insert(any());
    }

    @Test
    void createRequiresPositiveCurrentUserAndSuccessfulInsert() {
        when(currentUserProvider.getCurrentUserId()).thenReturn(null);
        when(policyDao.queryByPolicyName("daily")).thenReturn(null);

        LakeServiceException missingUser = assertThrows(
                LakeServiceException.class, () -> service.create(createRequest("daily")));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, missingUser.getLakeErrorCode());
        verify(policyDao, never()).insert(any());

        when(currentUserProvider.getCurrentUserId()).thenReturn(7);
        when(policyDao.insert(any())).thenReturn(0);
        LakeServiceException insertFailure = assertThrows(
                LakeServiceException.class, () -> service.create(createRequest("daily")));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, insertFailure.getLakeErrorCode());
    }

    @Test
    void pageUsesDaoPageAndMapsFiltersWithoutInMemoryPaging() {
        LakeLifecyclePolicyPageDTO request = new LakeLifecyclePolicyPageDTO();
        request.setPageNo(2);
        request.setPageSize(25);
        request.setPolicyName("day");
        request.setStatus(LakeLifecyclePolicyStatus.ACTIVE);
        request.setGranularity(LakePartitionGranularity.DAY);
        Page<LakeLifecyclePolicy> page = new Page<>(2, 25);
        page.setTotal(1);
        page.setRecords(List.of(policy(11L, 3, LakeLifecyclePolicyStatus.ACTIVE)));
        when(policyDao.queryPage(request)).thenReturn(page);

        PaginationResult<LakeLifecyclePolicyVO> result = service.page(request);

        assertEquals(1, result.getData().getPagination().getTotal());
        assertEquals(2, result.getData().getPagination().getPageNo());
        assertEquals(25, result.getData().getPagination().getPageSize());
        assertEquals(11L, result.getData().getBizData().get(0).getId());
        verify(policyDao).queryPage(request);
    }

    @Test
    void pageAppliesBoundedDefaultsAndRejectsOversizedPage() {
        Page<LakeLifecyclePolicy> page = new Page<>(1, 10);
        page.setRecords(List.of());
        when(policyDao.queryPage(any())).thenReturn(page);
        LakeLifecyclePolicyPageDTO request = new LakeLifecyclePolicyPageDTO();
        request.setPageNo(null);
        request.setPageSize(null);

        service.page(request);

        assertEquals(1, request.getPageNo());
        assertEquals(10, request.getPageSize());
        request.setPageSize(1001);
        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.page(request));
        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, exception.getLakeErrorCode());
    }

    @Test
    void updateUsesExpectedVersionAndIncrementsBusinessVersion() {
        LakeLifecyclePolicy existing = policy(11L, 3, LakeLifecyclePolicyStatus.DRAFT);
        when(policyDao.queryById(11L)).thenReturn(existing);
        when(policyDao.queryByPolicyNameExcludeId("weekly", 11L)).thenReturn(null);
        when(policyDao.updateIfVersion(any(), org.mockito.ArgumentMatchers.eq(3))).thenReturn(true);
        LakeLifecyclePolicyUpdateDTO request = updateRequest("weekly", 3);

        LakeLifecyclePolicyVO result = service.update(11L, request);

        assertEquals("weekly", result.getPolicyName());
        assertEquals(4, result.getVersion());
        assertEquals(7, result.getUpdateUserId());
        ArgumentCaptor<LakeLifecyclePolicy> captor = ArgumentCaptor.forClass(LakeLifecyclePolicy.class);
        verify(policyDao).updateIfVersion(captor.capture(), org.mockito.ArgumentMatchers.eq(3));
        assertEquals(4, captor.getValue().getVersion());
    }

    @Test
    void updateTrimsDescriptionBeforeComparingAndSaving() {
        LakeLifecyclePolicy existing = policy(11L, 3, LakeLifecyclePolicyStatus.DRAFT);
        existing.setDescription("keep recent partitions");
        when(policyDao.queryById(11L)).thenReturn(existing);
        when(policyDao.queryByPolicyNameExcludeId("weekly", 11L)).thenReturn(null);
        when(policyDao.updateIfVersion(any(), org.mockito.ArgumentMatchers.eq(3))).thenReturn(true);
        LakeLifecyclePolicyUpdateDTO request = updateRequest("weekly", 3);
        request.setDescription("  updated  ");

        LakeLifecyclePolicyVO result = service.update(11L, request);

        assertEquals("updated", result.getDescription());
    }

    @Test
    void updateAndDisableRequirePositiveCurrentUser() {
        LakeLifecyclePolicy existing = policy(11L, 3, LakeLifecyclePolicyStatus.DRAFT);
        when(policyDao.queryById(11L)).thenReturn(existing);
        when(policyDao.queryByPolicyNameExcludeId("weekly", 11L)).thenReturn(null);
        when(currentUserProvider.getCurrentUserId()).thenReturn(null);

        LakeServiceException updateFailure = assertThrows(
                LakeServiceException.class, () -> service.update(11L, updateRequest("weekly", 3)));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, updateFailure.getLakeErrorCode());
        verify(policyDao, never()).updateIfVersion(any(), any());

        existing = policy(11L, 3, LakeLifecyclePolicyStatus.ACTIVE);
        when(policyDao.queryById(11L)).thenReturn(existing);
        LakeLifecyclePolicyDisableDTO disableRequest = new LakeLifecyclePolicyDisableDTO();
        disableRequest.setExpectedVersion(3);
        LakeServiceException disableFailure = assertThrows(
                LakeServiceException.class, () -> service.disable(11L, disableRequest));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, disableFailure.getLakeErrorCode());
        verify(policyDao, never()).updateIfVersion(any(), any());
    }

    @Test
    void sameUpdateIsIdempotentAndDoesNotAdvanceVersion() {
        LakeLifecyclePolicy existing = policy(11L, 3, LakeLifecyclePolicyStatus.DRAFT);
        when(policyDao.queryById(11L)).thenReturn(existing);
        LakeLifecyclePolicyUpdateDTO request = updateRequest(existing.getPolicyName(), 3);
        request.setGranularity(existing.getGranularity());
        request.setRetentionCount(existing.getRetentionCount());
        request.setDescription(existing.getDescription());
        request.setStatus(existing.getStatus());

        LakeLifecyclePolicyVO result = service.update(11L, request);

        assertEquals(3, result.getVersion());
        verify(policyDao, never()).updateIfVersion(any(), any());
        verify(policyDao, never()).queryByPolicyNameExcludeId(any(), anyLong());
    }

    @Test
    void updateRejectsStaleVersionAndDisabledPolicy() {
        LakeLifecyclePolicy existing = policy(11L, 3, LakeLifecyclePolicyStatus.DRAFT);
        when(policyDao.queryById(11L)).thenReturn(existing);
        LakeLifecyclePolicyUpdateDTO stale = updateRequest("weekly", 2);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.update(11L, stale));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(policyDao, never()).updateIfVersion(any(), any());

        existing = policy(11L, 4, LakeLifecyclePolicyStatus.DISABLED);
        when(policyDao.queryById(11L)).thenReturn(existing);
        exception = assertThrows(
                LakeServiceException.class, () -> service.update(11L, updateRequest("weekly", 4)));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
    }

    @Test
    void disableUsesCasAndRepeatedDisableIsIdempotent() {
        LakeLifecyclePolicy existing = policy(11L, 3, LakeLifecyclePolicyStatus.ACTIVE);
        when(policyDao.queryById(11L)).thenReturn(existing);
        when(policyDao.updateIfVersion(any(), org.mockito.ArgumentMatchers.eq(3))).thenReturn(true);
        LakeLifecyclePolicyDisableDTO request = new LakeLifecyclePolicyDisableDTO();
        request.setExpectedVersion(3);

        LakeLifecyclePolicyVO disabled = service.disable(11L, request);

        assertEquals(LakeLifecyclePolicyStatus.DISABLED, disabled.getStatus());
        assertEquals(4, disabled.getVersion());
        verify(policyDao).updateIfVersion(any(), org.mockito.ArgumentMatchers.eq(3));

        LakeLifecyclePolicy alreadyDisabled = policy(11L, 4, LakeLifecyclePolicyStatus.DISABLED);
        when(policyDao.queryById(11L)).thenReturn(alreadyDisabled);
        request.setExpectedVersion(3);
        LakeLifecyclePolicyVO repeated = service.disable(11L, request);
        assertSame(alreadyDisabled.getVersion(), repeated.getVersion());
        assertEquals(LakeLifecyclePolicyStatus.DISABLED, repeated.getStatus());
    }

    @Test
    void disableRequiresExpectedVersionAndCasFailureIsConflict() {
        LakeLifecyclePolicy existing = policy(11L, 3, LakeLifecyclePolicyStatus.ACTIVE);
        when(policyDao.queryById(11L)).thenReturn(existing);
        LakeLifecyclePolicyDisableDTO request = new LakeLifecyclePolicyDisableDTO();

        LakeServiceException missing = assertThrows(
                LakeServiceException.class, () -> service.disable(11L, request));
        assertEquals(LakeErrorCode.LAKE_REQUEST_INVALID, missing.getLakeErrorCode());

        request.setExpectedVersion(3);
        when(policyDao.updateIfVersion(any(), org.mockito.ArgumentMatchers.eq(3))).thenReturn(false);
        LakeServiceException conflict = assertThrows(
                LakeServiceException.class, () -> service.disable(11L, request));
        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, conflict.getLakeErrorCode());
    }

    private LakeLifecyclePolicyCreateDTO createRequest(String name) {
        LakeLifecyclePolicyCreateDTO request = new LakeLifecyclePolicyCreateDTO();
        request.setPolicyName(name);
        request.setGranularity(LakePartitionGranularity.DAY);
        request.setRetentionCount(7);
        request.setDescription("keep recent partitions");
        return request;
    }

    private LakeLifecyclePolicyUpdateDTO updateRequest(String name, int expectedVersion) {
        LakeLifecyclePolicyUpdateDTO request = new LakeLifecyclePolicyUpdateDTO();
        request.setPolicyName(name);
        request.setGranularity(LakePartitionGranularity.MONTH);
        request.setRetentionCount(14);
        request.setDescription("updated");
        request.setStatus(LakeLifecyclePolicyStatus.ACTIVE);
        request.setExpectedVersion(expectedVersion);
        return request;
    }

    private LakeLifecyclePolicy policy(
            Long id, int version, LakeLifecyclePolicyStatus status) {
        LakeLifecyclePolicy policy = new LakeLifecyclePolicy();
        policy.setId(id);
        policy.setPolicyName("daily");
        policy.setVersion(version);
        policy.setStatus(status);
        policy.setGranularity(LakePartitionGranularity.DAY);
        policy.setRetentionCount(7);
        policy.setDescription("keep recent partitions");
        policy.setCreateUserId(2);
        policy.setUpdateUserId(2);
        policy.setCreateTime(new Date(1000));
        policy.setUpdateTime(new Date(2000));
        return policy;
    }
}
