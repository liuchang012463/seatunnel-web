package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.mapper.LakeSourceObjectRefMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.springframework.stereotype.Repository;

@Repository
public class LakeSourceObjectRefDaoImpl extends BaseDao<LakeSourceObjectRef, LakeSourceObjectRefMapper>
        implements LakeSourceObjectRefDao {

    private final LakeSourceObjectRefMapper mapper;

    public LakeSourceObjectRefDaoImpl(@NonNull LakeSourceObjectRefMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeSourceObjectRef queryActiveById(Long id) {
        if (id == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<LakeSourceObjectRef>()
                .eq(LakeSourceObjectRef::getId, id)
                .eq(LakeSourceObjectRef::getDeleted, false));
    }

    @Override
    public LakeSourceObjectRef queryByIdIncludingDeleted(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    @Override
    public LakeSourceObjectRef queryByOmEntityId(String omEntityId) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeSourceObjectRef>()
                .eq(LakeSourceObjectRef::getOmEntityId, omEntityId)
                .eq(LakeSourceObjectRef::getDeleted, false));
    }

    @Override
    public LakeSourceObjectRef queryBySourceDataSourceIdAndOmEntityId(
            Long sourceDataSourceId, String omEntityId) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeSourceObjectRef>()
                .eq(LakeSourceObjectRef::getSourceDataSourceId, sourceDataSourceId)
                .eq(LakeSourceObjectRef::getOmEntityId, omEntityId)
                .eq(LakeSourceObjectRef::getDeleted, false));
    }

    @Override
    public boolean updateIfTokenAndVersion(
            LakeSourceObjectRef entity, String operationToken, Integer lockVersion) {
        return updateIfTokenAndVersion(entity, operationToken, lockVersion, true);
    }

    @Override
    public boolean updateIfTokenAndVersionIncludingDeleted(
            LakeSourceObjectRef entity, String operationToken, Integer lockVersion) {
        return updateIfTokenAndVersion(entity, operationToken, lockVersion, false);
    }

    private boolean updateIfTokenAndVersion(
            LakeSourceObjectRef entity, String operationToken, Integer lockVersion, boolean activeOnly) {
        if (entity == null || entity.getId() == null || lockVersion == null) {
            return false;
        }
        entity.setLockVersion(lockVersion + 1);
        LambdaUpdateWrapper<LakeSourceObjectRef> wrapper = new LambdaUpdateWrapper<LakeSourceObjectRef>()
                .eq(LakeSourceObjectRef::getId, entity.getId())
                .eq(LakeSourceObjectRef::getLockVersion, lockVersion);
        if (activeOnly) {
            wrapper.eq(LakeSourceObjectRef::getDeleted, false);
        }
        if (operationToken == null) {
            wrapper.isNull(LakeSourceObjectRef::getOperationToken);
        } else {
            wrapper.eq(LakeSourceObjectRef::getOperationToken, operationToken);
        }
        return mapper.update(entity, wrapper) > 0;
    }
}
