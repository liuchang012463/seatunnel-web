package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.operation.LakeOperationTransactionBoundary;
import org.apache.seatunnel.web.api.lake.operation.LakeManagedTableOperationPublication;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeManagedTableLifecycleCreatePersistenceServiceTest {

    @Test
    void retryReopensMappingAndLifecycleBindingWithOneSharedLease() {
        LakeOdsTableMappingDao mappingDao = mock(LakeOdsTableMappingDao.class);
        LakeTableLifecycleBindingDao lifecycleDao = mock(LakeTableLifecycleBindingDao.class);
        LakeResourceOperationDao operationDao = mock(LakeResourceOperationDao.class);

        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(501L);
        mapping.setGeneration(2);
        mapping.setLockVersion(3);
        mapping.setDeleted(false);
        mapping.setOperationToken(null);
        mapping.setResourceStatus(LakeResourceStatus.ERROR);
        mapping.setActualTableExists(true);
        mapping.setTargetConsistencyStatus(LakeConsistencyStatus.DRIFT);
        mapping.setTargetContractHash("contract-hash");

        LakeTableLifecycleBinding binding = new LakeTableLifecycleBinding();
        binding.setId(701L);
        binding.setTableMappingId(501L);
        binding.setLockVersion(4);
        binding.setStatus(LakeLifecycleBindingStatus.ERROR);
        binding.setOperationToken(null);
        binding.setRetentionCount(7);
        binding.setActualRetentionCount(6);
        binding.setActualPartitionSummaryJson("stale-summary");

        when(mappingDao.queryByIdIncludingDeleted(501L)).thenReturn(mapping);
        when(lifecycleDao.queryByTableMappingId(501L)).thenReturn(binding);
        when(lifecycleDao.updateIfTokenAndVersion(any(LakeTableLifecycleBinding.class),
                eq(null), eq(4))).thenAnswer(invocation -> {
            LakeTableLifecycleBinding value = invocation.getArgument(0);
            value.setLockVersion(5);
            return true;
        });
        when(mappingDao.updateIfTokenAndVersion(any(LakeOdsTableMapping.class),
                eq(null), eq(3))).thenAnswer(invocation -> {
            LakeOdsTableMapping value = invocation.getArgument(0);
            value.setLockVersion(4);
            return true;
        });
        when(operationDao.insert(any(LakeResourceOperation.class))).thenReturn(1);

        LakeOperationTransactionBoundary boundary = new LakeOperationTransactionBoundary() {
            @Override
            public <T> T requiresNew(java.util.function.Supplier<T> action) {
                return action.get();
            }
        };
        LakeManagedTableLifecycleCreatePersistenceService service =
                new LakeManagedTableLifecycleCreatePersistenceService(
                        mappingDao, lifecycleDao, operationDao, boundary,
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        LakeManagedTableLifecycleCreatePersistenceService.StartResult result = service.startRetry(
                mapping,
                new LakeManagedTableLifecycleCreatePersistenceService.LifecycleSpec(
                        901L, 3, "event_time", LakePartitionGranularity.DAY,
                        7, "{\"policyId\":901}"),
                42);

        assertNotNull(result.handle());
        assertEquals(501L, result.handle().resourceId());
        assertEquals(2, result.handle().generation());
        assertEquals(4, result.handle().lockVersion());
        assertNotNull(result.handle().operationToken());
        assertEquals(701L, result.publication().lifecycleBindingId());
        assertEquals(5, result.publication().lifecycleLockVersion());
        assertEquals(7, result.publication().retentionCount());

        assertEquals(LakeResourceStatus.CREATING, mapping.getResourceStatus());
        assertEquals(result.handle().operationToken(), mapping.getOperationToken());
        assertFalse(mapping.getActualTableExists());
        assertEquals(LakeConsistencyStatus.UNKNOWN, mapping.getTargetConsistencyStatus());
        assertEquals(LakeLifecycleBindingStatus.PENDING, binding.getStatus());
        assertEquals(result.handle().operationToken(), binding.getOperationToken());
        assertEquals(5, binding.getLockVersion());
        assertEquals(7, binding.getRetentionCount());
        assertNull(binding.getActualRetentionCount());
        assertNull(binding.getActualPartitionSummaryJson());

        org.mockito.ArgumentCaptor<LakeResourceOperation> operationCaptor =
                org.mockito.ArgumentCaptor.forClass(LakeResourceOperation.class);
        // The shared token and retry identity are asserted through the object
        // passed to the DAO without exposing any credential-bearing payload.
        verify(operationDao).insert(operationCaptor.capture());
        LakeResourceOperation operation = operationCaptor.getValue();
        assertEquals(LakeResourceTypes.ODS_TABLE_MAPPING, operation.getResourceType());
        assertEquals(LakeOperationType.RETRY, operation.getOperationType());
        assertEquals(LakeOperationStatus.PENDING, operation.getStatus());
        assertEquals(result.handle().operationToken(), operation.getOperationToken());
    }
}
