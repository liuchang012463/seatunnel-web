package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationTransactionBoundary;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeLifecycleApplyPersistenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void tx1PersistsPendingBindingAndOperationAgainstBindingId() {
        LakeOdsTableMappingDao mappingDao = mock(LakeOdsTableMappingDao.class);
        LakeLifecyclePolicyDao policyDao = mock(LakeLifecyclePolicyDao.class);
        LakeTableLifecycleBindingDao bindingDao = mock(LakeTableLifecycleBindingDao.class);
        LakeResourceOperationDao operationDao = mock(LakeResourceOperationDao.class);
        RecordingBoundary boundary = new RecordingBoundary();
        when(mappingDao.queryActiveById(501L)).thenReturn(mapping());
        when(policyDao.queryById(901L)).thenReturn(policy());
        when(bindingDao.queryByTableMappingId(501L)).thenReturn(null);
        when(operationDao.insert(any(LakeResourceOperation.class))).thenReturn(1);
        when(bindingDao.insert(any(LakeTableLifecycleBinding.class))).thenReturn(1);

        LakeLifecycleApplyPersistenceService service = new LakeLifecycleApplyPersistenceService(
                mappingDao, policyDao, bindingDao, operationDao, boundary,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        LakeLifecycleApplyPersistenceService.StartResult result = service.start(request());

        assertFalse(result.idempotent());
        assertNotNull(result.handle());
        assertEquals(1, boundary.count.get());
        assertEquals(LakeLifecycleBindingStatus.PENDING, result.binding().getStatus());
        org.mockito.ArgumentCaptor<LakeResourceOperation> captor =
                org.mockito.ArgumentCaptor.forClass(LakeResourceOperation.class);
        verify(operationDao).insert(captor.capture());
        LakeResourceOperation operation = captor.getValue();
        assertEquals(LakeResourceTypes.TABLE_LIFECYCLE, operation.getResourceType());
        assertEquals(result.binding().getId(), operation.getResourceId());
        assertEquals(result.binding().getGeneration(), operation.getGeneration());
        assertEquals(LakeOperationType.ALTER_RETENTION, operation.getOperationType());
        assertEquals(LakeOperationStatus.PENDING, operation.getStatus());
        verify(bindingDao).insert(result.binding());
    }

    @Test
    void exactActiveBindingRequiresFreshActualAndCompleteSummaryForIdempotency() {
        LakeOdsTableMappingDao mappingDao = mock(LakeOdsTableMappingDao.class);
        LakeLifecyclePolicyDao policyDao = mock(LakeLifecyclePolicyDao.class);
        LakeTableLifecycleBindingDao bindingDao = mock(LakeTableLifecycleBindingDao.class);
        LakeResourceOperationDao operationDao = mock(LakeResourceOperationDao.class);
        RecordingBoundary boundary = new RecordingBoundary();
        when(mappingDao.queryActiveById(501L)).thenReturn(mapping());
        when(policyDao.queryById(901L)).thenReturn(policy());
        LakeTableLifecycleBinding binding = activeBinding();
        when(bindingDao.queryByTableMappingId(501L)).thenReturn(binding);
        when(operationDao.insert(any(LakeResourceOperation.class))).thenReturn(1);
        when(bindingDao.updateIfTokenAndVersion(any(), any(), any())).thenReturn(true);

        LakeLifecycleApplyPersistenceService service = new LakeLifecycleApplyPersistenceService(
                mappingDao, policyDao, bindingDao, operationDao, boundary,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        LakeLifecycleApplyPersistenceService.StartResult result = service.start(
                request().withFreshObservation(7, true));

        assertTrue(result.idempotent());
        assertEquals(binding, result.binding());
    }

    @Test
    void staleFinalizeDoesNotPublishActiveStateAndMarksOperationIgnored() {
        LakeOdsTableMappingDao mappingDao = mock(LakeOdsTableMappingDao.class);
        LakeLifecyclePolicyDao policyDao = mock(LakeLifecyclePolicyDao.class);
        LakeTableLifecycleBindingDao bindingDao = mock(LakeTableLifecycleBindingDao.class);
        LakeResourceOperationDao operationDao = mock(LakeResourceOperationDao.class);
        RecordingBoundary boundary = new RecordingBoundary();
        LakeResourceOperation operation = new LakeResourceOperation();
        operation.setId(2001L);
        operation.setGeneration(1);
        operation.setOperationToken("token");
        operation.setStatus(LakeOperationStatus.PENDING);
        LakeTableLifecycleBinding binding = activeBinding();
        binding.setStatus(LakeLifecycleBindingStatus.PENDING);
        binding.setOperationToken("token");
        binding.setLockVersion(4);
        when(operationDao.queryById(2001L)).thenReturn(operation);
        when(bindingDao.queryById(1001L)).thenReturn(binding);
        when(bindingDao.updateIfTokenAndVersion(any(), any(), any())).thenReturn(false);
        when(operationDao.updateStatusIfToken(any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        LakeLifecycleApplyPersistenceService service = new LakeLifecycleApplyPersistenceService(
                mappingDao, policyDao, bindingDao, operationDao, boundary,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        LakeLifecycleApplyPersistenceService.OperationHandle handle =
                new LakeLifecycleApplyPersistenceService.OperationHandle(
                        2001L, 501L, 1, 1001L, 1, 4, "token", 7);
        DorisPartitionSummary summary = new DorisPartitionSummary(
                0, 0, 0, 0, 0, List.of(), NOW);

        assertFalse(service.finalizeSuccess(handle, 7, summary, 7));
        verify(operationDao).updateStatusIfToken(
                2001L, "token", LakeOperationStatus.PENDING,
                LakeOperationStatus.IGNORED, "LAKE_OPERATION_STALE",
                "Stale lifecycle operation result ignored");
    }

    private static LakeLifecycleApplyPersistenceService.StartRequest request() {
        return new LakeLifecycleApplyPersistenceService.StartRequest(
                501L, 901L, 1, "event_time", LakePartitionGranularity.DAY,
                7, 1, 1, null, null, null, null, "request-hash", 7, true);
    }

    private static LakeOdsTableMapping mapping() {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(501L);
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setGeneration(1);
        mapping.setLockVersion(1);
        mapping.setDeleted(false);
        return mapping;
    }

    private static LakeLifecyclePolicy policy() {
        LakeLifecyclePolicy policy = new LakeLifecyclePolicy();
        policy.setId(901L);
        policy.setVersion(1);
        policy.setStatus(LakeLifecyclePolicyStatus.ACTIVE);
        policy.setGranularity(LakePartitionGranularity.DAY);
        policy.setRetentionCount(7);
        return policy;
    }

    private static LakeTableLifecycleBinding activeBinding() {
        LakeTableLifecycleBinding binding = new LakeTableLifecycleBinding();
        binding.setId(1001L);
        binding.setTableMappingId(501L);
        binding.setPolicyId(901L);
        binding.setPolicyVersion(1);
        binding.setPartitionColumn("event_time");
        binding.setGranularity(LakePartitionGranularity.DAY);
        binding.setRetentionCount(7);
        binding.setActualRetentionCount(7);
        binding.setActualPartitionSummaryJson("{}");
        binding.setLastObservedAt(Date.from(NOW));
        binding.setPolicySnapshotJson(
                "{\"policyId\":901,\"version\":1,\"granularity\":\"DAY\","
                        + "\"retentionCount\":7}");
        binding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        binding.setGeneration(1);
        binding.setLockVersion(4);
        return binding;
    }

    private static final class RecordingBoundary implements LakeOperationTransactionBoundary {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public <T> T requiresNew(java.util.function.Supplier<T> action) {
            count.incrementAndGet();
            return action.get();
        }
    }
}
