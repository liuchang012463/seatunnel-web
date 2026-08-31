package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.mapper.LakeOdsTableMappingMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

@Repository
public class LakeOdsTableMappingDaoImpl extends BaseDao<LakeOdsTableMapping, LakeOdsTableMappingMapper>
        implements LakeOdsTableMappingDao {

    private final LakeOdsTableMappingMapper mapper;

    public LakeOdsTableMappingDaoImpl(@NonNull LakeOdsTableMappingMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeOdsTableMapping queryActiveById(Long id) {
        if (id == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getId, id)
                .eq(LakeOdsTableMapping::getDeleted, false));
    }

    @Override
    public LakeOdsTableMapping queryByIdIncludingDeleted(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    @Override
    public List<LakeOdsTableMapping> queryByOdsDatabaseBindingId(Long odsDatabaseBindingId) {
        if (odsDatabaseBindingId == null) {
            return Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .eq(LakeOdsTableMapping::getDeleted, false)
                .orderByAsc(LakeOdsTableMapping::getTargetTableName));
    }

    @Override
    public LakeOdsTableMapping queryByBindingIdAndTargetTable(
            Long odsDatabaseBindingId, String targetTableName) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .eq(LakeOdsTableMapping::getTargetTableName, targetTableName)
                .eq(LakeOdsTableMapping::getDeleted, false));
    }

    @Override
    public LakeOdsTableMapping queryByBindingIdAndTargetTableIncludingDeleted(
            Long odsDatabaseBindingId, String targetTableName) {
        if (odsDatabaseBindingId == null || targetTableName == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .eq(LakeOdsTableMapping::getTargetTableName, targetTableName));
    }

    @Override
    public LakeOdsTableMapping queryByBindingIdAndSourceObject(
            Long odsDatabaseBindingId, Long sourceObjectRefId) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .eq(LakeOdsTableMapping::getSourceObjectRefId, sourceObjectRefId)
                .eq(LakeOdsTableMapping::getDeleted, false));
    }

    @Override
    public LakeOdsTableMapping queryByBindingIdAndSourceObjectIncludingDeleted(
            Long odsDatabaseBindingId, Long sourceObjectRefId) {
        if (odsDatabaseBindingId == null || sourceObjectRefId == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .eq(LakeOdsTableMapping::getSourceObjectRefId, sourceObjectRefId));
    }

    @Override
    public boolean updateIfTokenAndVersion(
            LakeOdsTableMapping entity, String operationToken, Integer lockVersion) {
        return updateIfTokenAndVersion(entity, operationToken, lockVersion, true);
    }

    @Override
    public boolean updateIfTokenAndVersionIncludingDeleted(
            LakeOdsTableMapping entity, String operationToken, Integer lockVersion) {
        return updateIfTokenAndVersion(entity, operationToken, lockVersion, false);
    }

    private boolean updateIfTokenAndVersion(
            LakeOdsTableMapping entity, String operationToken, Integer lockVersion, boolean activeOnly) {
        if (entity == null || entity.getId() == null || lockVersion == null) {
            return false;
        }
        entity.setLockVersion(lockVersion + 1);
        LambdaUpdateWrapper<LakeOdsTableMapping> wrapper = new LambdaUpdateWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getId, entity.getId())
                .eq(LakeOdsTableMapping::getLockVersion, lockVersion);
        if (activeOnly) {
            wrapper.eq(LakeOdsTableMapping::getDeleted, false);
        }
        if (operationToken == null) {
            wrapper.isNull(LakeOdsTableMapping::getOperationToken);
        } else {
            wrapper.eq(LakeOdsTableMapping::getOperationToken, operationToken);
        }
        return mapper.update(entity, wrapper) > 0;
    }
}
