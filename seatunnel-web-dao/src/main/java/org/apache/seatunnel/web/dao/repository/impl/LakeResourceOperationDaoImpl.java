package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.mapper.LakeResourceOperationMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Date;
import java.util.List;

@Repository
public class LakeResourceOperationDaoImpl
        extends BaseDao<LakeResourceOperation, LakeResourceOperationMapper>
        implements LakeResourceOperationDao {

    private final LakeResourceOperationMapper mapper;

    public LakeResourceOperationDaoImpl(@NonNull LakeResourceOperationMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeResourceOperation queryByOperationToken(String operationToken) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeResourceOperation>()
                .eq(LakeResourceOperation::getOperationToken, operationToken));
    }

    @Override
    public List<LakeResourceOperation> queryByResource(String resourceType, Long resourceId) {
        if (resourceType == null || resourceId == null) {
            return Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<LakeResourceOperation>()
                .eq(LakeResourceOperation::getResourceType, resourceType)
                .eq(LakeResourceOperation::getResourceId, resourceId)
                .orderByDesc(LakeResourceOperation::getStartedAt));
    }

    @Override
    public List<LakeResourceOperation> queryByStatus(LakeOperationStatus status) {
        if (status == null) {
            return Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<LakeResourceOperation>()
                .eq(LakeResourceOperation::getStatus, status)
                .orderByAsc(LakeResourceOperation::getStartedAt));
    }

    @Override
    public boolean updateStatusIfToken(
            Long id, String operationToken, LakeOperationStatus status,
            String errorCode, String errorSummary) {
        if (id == null || operationToken == null || status == null) {
            return false;
        }
        Date now = new Date();
        return mapper.update(null, new LambdaUpdateWrapper<LakeResourceOperation>()
                .eq(LakeResourceOperation::getId, id)
                .eq(LakeResourceOperation::getOperationToken, operationToken)
                .set(LakeResourceOperation::getStatus, status)
                .set(LakeResourceOperation::getErrorCode, errorCode)
                .set(LakeResourceOperation::getErrorSummary, errorSummary)
                .set(LakeResourceOperation::getFinishedAt,
                        status == LakeOperationStatus.PENDING || status == LakeOperationStatus.RUNNING ? null : now)) > 0;
    }
}
