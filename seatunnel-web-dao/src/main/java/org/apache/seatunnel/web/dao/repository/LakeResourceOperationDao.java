package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;

import java.util.List;

public interface LakeResourceOperationDao extends IDao<LakeResourceOperation> {

    LakeResourceOperation queryByOperationToken(String operationToken);

    List<LakeResourceOperation> queryByResource(String resourceType, Long resourceId);

    List<LakeResourceOperation> queryByStatus(LakeOperationStatus status);

    boolean updateStatusIfToken(
            Long id, String operationToken, LakeOperationStatus status, String errorCode, String errorSummary);

    /**
     * Compare-and-set operation status as well as id and token.  The expected
     * status is required by terminal-state transitions so a repeated callback
     * cannot turn SUCCEEDED/FAILED/IGNORED back into another terminal state.
     */
    default boolean updateStatusIfToken(
            Long id, String operationToken, LakeOperationStatus expectedStatus,
            LakeOperationStatus status, String errorCode, String errorSummary) {
        return updateStatusIfToken(id, operationToken, status, errorCode, errorSummary);
    }
}
