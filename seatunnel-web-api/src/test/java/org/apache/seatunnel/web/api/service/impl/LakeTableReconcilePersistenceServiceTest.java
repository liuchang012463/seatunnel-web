package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.table.LakeTableDriftEvaluator;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeTableReconcilePersistenceServiceTest {

    @Mock private LakeOdsTableMappingDao tableMappingDao;

    private LakeTableReconcilePersistenceService persistence;

    @BeforeEach
    void setUp() {
        persistence = new LakeTableReconcilePersistenceService(tableMappingDao);
        org.mockito.Mockito.lenient()
                .when(tableMappingDao.updateIfTokenAndVersion(any(), eq(null), eq(4)))
                .thenReturn(true);
    }

    @Test
    void writesOnlyCachedDimensionsAndMarksMissingTargetFalse() {
        LakeOdsTableMapping mapping = mapping(LakeResourceStatus.READY);
        LakeTableDriftEvaluator.Evaluation evaluation = evaluation(
                LakeConsistencyStatus.DRIFT, LakeConsistencyStatus.MISSING,
                LakeConsistencyStatus.UNBOUND, LakeTableDriftEvaluator.TARGET_TABLE_MISSING);

        LakeOdsTableMapping result = persistence.persist(mapping, evaluation, 9);

        assertEquals(LakeConsistencyStatus.DRIFT, result.getSourceConsistencyStatus());
        assertEquals(LakeConsistencyStatus.MISSING, result.getTargetConsistencyStatus());
        assertEquals(LakeConsistencyStatus.UNBOUND, result.getTaskConsistencyStatus());
        assertFalse(result.getActualTableExists());
        assertEquals(9, result.getUpdateUserId());
        assertNotNull(result.getLastReconcileAt());
        verify(tableMappingDao).updateIfTokenAndVersion(mapping, null, 4);
    }

    @Test
    void unknownTargetPreservesLastObservation() {
        LakeOdsTableMapping mapping = mapping(LakeResourceStatus.UNKNOWN);
        mapping.setActualTableExists(true);
        LakeTableDriftEvaluator.Evaluation evaluation = evaluation(
                LakeConsistencyStatus.CONSISTENT, LakeConsistencyStatus.UNKNOWN,
                LakeConsistencyStatus.UNBOUND, "LAKE_DORIS_UNAVAILABLE");

        persistence.persist(mapping, evaluation, 9);

        assertTrue(mapping.getActualTableExists());
        assertEquals(LakeConsistencyStatus.UNKNOWN, mapping.getTargetConsistencyStatus());
    }

    @Test
    void stableStatusesAreAllowlistedAndPendingCreateIsNotOverwritten() {
        LakeOdsTableMapping mapping = mapping(LakeResourceStatus.PENDING_CREATE);

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> persistence.persist(mapping, evaluation(
                        LakeConsistencyStatus.CONSISTENT, LakeConsistencyStatus.CONSISTENT,
                        LakeConsistencyStatus.UNBOUND, "OK"), 9));

        assertEquals(LakeErrorCode.LAKE_OPERATION_STALE, exception.getLakeErrorCode());
        verify(tableMappingDao, never()).updateIfTokenAndVersion(any(), any(), any());
    }

    @Test
    void casFailureIsStableConflict() {
        LakeOdsTableMapping mapping = mapping(LakeResourceStatus.READY);
        when(tableMappingDao.updateIfTokenAndVersion(any(), eq(null), eq(4))).thenReturn(false);

        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> persistence.persist(mapping, evaluation(
                        LakeConsistencyStatus.CONSISTENT, LakeConsistencyStatus.CONSISTENT,
                        LakeConsistencyStatus.UNBOUND, "OK"), 9));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
    }

    @Test
    void deletedOrOperationTokenMappingsCannotBeReconciled() {
        LakeOdsTableMapping deleted = mapping(LakeResourceStatus.READY);
        deleted.setDeleted(true);
        LakeOdsTableMapping changing = mapping(LakeResourceStatus.READY);
        changing.setOperationToken("ddl-token");

        assertEquals(LakeErrorCode.LAKE_OPERATION_STALE,
                assertThrows(LakeServiceException.class, () -> persistence.persist(
                        changing, evaluation(LakeConsistencyStatus.CONSISTENT,
                                LakeConsistencyStatus.CONSISTENT,
                                LakeConsistencyStatus.UNBOUND, "OK"), 9)).getLakeErrorCode());
        assertEquals(LakeErrorCode.LAKE_OPERATION_STALE,
                assertThrows(LakeServiceException.class, () -> persistence.persist(
                        deleted, evaluation(LakeConsistencyStatus.CONSISTENT,
                                LakeConsistencyStatus.CONSISTENT,
                                LakeConsistencyStatus.UNBOUND, "OK"), 9)).getLakeErrorCode());
    }

    private static LakeOdsTableMapping mapping(LakeResourceStatus status) {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(70L);
        mapping.setManagementLevel(LakeManagementLevel.UNMANAGED);
        mapping.setResourceStatus(status);
        mapping.setLockVersion(4);
        mapping.setGeneration(2);
        mapping.setActualTableExists(false);
        mapping.setDeleted(false);
        return mapping;
    }

    private static LakeTableDriftEvaluator.Evaluation evaluation(
            LakeConsistencyStatus source,
            LakeConsistencyStatus target,
            LakeConsistencyStatus task,
            String targetReason) {
        return new LakeTableDriftEvaluator.Evaluation(
                70L, LakeManagementLevel.UNMANAGED,
                new LakeTableDriftEvaluator.DimensionResult(source, "SOURCE", "source"),
                new LakeTableDriftEvaluator.DimensionResult(target, targetReason, "target"),
                new LakeTableDriftEvaluator.DimensionResult(task, "TASK", "task"),
                LakeTableDriftEvaluator.aggregateStatus(source, target, task),
                java.util.List.of());
    }
}
