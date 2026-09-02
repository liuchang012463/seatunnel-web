package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.table.LakeTableDriftEvaluator;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/**
 * Short local transaction for persisting an already-computed table evaluation.
 * It deliberately has no external clients and performs one optimistic CAS.
 */
@Service
public class LakeTableReconcilePersistenceService {

    private final LakeOdsTableMappingDao tableMappingDao;

    @Autowired
    public LakeTableReconcilePersistenceService(LakeOdsTableMappingDao tableMappingDao) {
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
    }

    @Transactional(rollbackFor = Exception.class)
    public LakeOdsTableMapping persist(
            LakeOdsTableMapping mapping,
            LakeTableDriftEvaluator.Evaluation evaluation,
            Integer userId) {
        return persist(mapping, evaluation, userId, null, false);
    }

    /**
     * Persists a bounded actual-contract observation together with the
     * consistency CAS.  The observation is supplied by the orchestration
     * service after its explicit remote read; this class never contacts Doris.
     */
    @Transactional(rollbackFor = Exception.class)
    public LakeOdsTableMapping persist(
            LakeOdsTableMapping mapping,
            LakeTableDriftEvaluator.Evaluation evaluation,
            Integer userId,
            String actualContractJson,
            boolean actualObserved) {
        if (mapping == null || evaluation == null || userId == null || userId <= 0) {
            throw invalid("Lake table reconcile request is invalid");
        }
        Integer lockVersion = mapping.getLockVersion();
        if (lockVersion == null || mapping.getOperationToken() != null
                || Boolean.TRUE.equals(mapping.getDeleted())
                || !isStableReconcileStatus(mapping.getResourceStatus())) {
            throw stale("Lake table mapping changed during reconcile");
        }
        mapping.setSourceConsistencyStatus(evaluation.source().status());
        mapping.setTargetConsistencyStatus(evaluation.target().status());
        mapping.setTaskConsistencyStatus(evaluation.task().status());
        if (LakeTableDriftEvaluator.TARGET_TABLE_MISSING.equals(
                evaluation.target().reasonCode())) {
            mapping.setActualTableExists(false);
        } else if (evaluation.target().status() == LakeConsistencyStatus.CONSISTENT
                || evaluation.target().status() == LakeConsistencyStatus.DRIFT) {
            mapping.setActualTableExists(true);
        }
        if (actualObserved) {
            mapping.setActualContractJson(actualContractJson);
        }
        mapping.setLastReconcileAt(new Date());
        mapping.setUpdateUserId(userId);
        if (!tableMappingDao.updateIfTokenAndVersion(mapping, null, lockVersion)) {
            throw conflict("Lake table mapping changed during reconcile");
        }
        return mapping;
    }

    private static boolean isStableReconcileStatus(org.apache.seatunnel.web.common.enums.LakeResourceStatus status) {
        return status == org.apache.seatunnel.web.common.enums.LakeResourceStatus.READY
                || status == org.apache.seatunnel.web.common.enums.LakeResourceStatus.ERROR
                || status == org.apache.seatunnel.web.common.enums.LakeResourceStatus.CREATE_FAILED
                || status == org.apache.seatunnel.web.common.enums.LakeResourceStatus.MISSING
                || status == org.apache.seatunnel.web.common.enums.LakeResourceStatus.UNKNOWN;
    }

    private static LakeServiceException invalid(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID, message);
    }

    private static LakeServiceException conflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, message);
    }

    private static LakeServiceException stale(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_OPERATION_STALE, message);
    }
}
