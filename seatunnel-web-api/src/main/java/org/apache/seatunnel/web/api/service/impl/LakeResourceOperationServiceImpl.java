package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeOperationLogRedactor;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationVO;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.api.service.LakeResourceOperationService;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/** Default read-only projection of the durable lake operation journal. */
@Service
public class LakeResourceOperationServiceImpl implements LakeResourceOperationService {

    private static final int MAX_ROWS = 100;

    private final LakeResourceOperationDao operationDao;

    @Autowired
    public LakeResourceOperationServiceImpl(LakeResourceOperationDao operationDao) {
        this.operationDao = operationDao;
    }

    @Override
    public List<LakeResourceOperationVO> list(String resourceType, Long resourceId) {
        final String normalizedType;
        try {
            normalizedType = LakeResourceTypes.normalize(resourceType);
        } catch (RuntimeException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID,
                    "Unsupported lake operation resource type");
        }
        if (resourceId == null || resourceId <= 0) {
            throw new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID,
                    "Lake operation resource id must be positive");
        }
        List<LakeResourceOperation> rows = operationDao.queryByResource(normalizedType, resourceId);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream()
                .filter(row -> row != null)
                .limit(MAX_ROWS)
                .map(LakeResourceOperationServiceImpl::toVO)
                .toList();
    }

    private static LakeResourceOperationVO toVO(LakeResourceOperation row) {
        LakeResourceOperationVO result = new LakeResourceOperationVO();
        result.setId(row.getId());
        result.setResourceType(row.getResourceType());
        result.setResourceId(row.getResourceId());
        result.setGeneration(row.getGeneration());
        result.setOperationType(row.getOperationType());
        result.setStatus(row.getStatus());
        result.setStartedAt(row.getStartedAt());
        result.setFinishedAt(row.getFinishedAt());
        result.setErrorCode(row.getErrorCode());
        result.setErrorSummary(LakeOperationLogRedactor.summary(row.getErrorSummary()));
        result.setOperatorId(row.getOperatorId());
        return result;
    }
}
