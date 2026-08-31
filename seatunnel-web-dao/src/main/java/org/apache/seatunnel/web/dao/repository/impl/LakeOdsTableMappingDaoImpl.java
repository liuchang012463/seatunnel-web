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
    public List<LakeOdsTableMapping> queryByOdsDatabaseBindingId(Long odsDatabaseBindingId) {
        if (odsDatabaseBindingId == null) {
            return Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .orderByAsc(LakeOdsTableMapping::getTargetTableName));
    }

    @Override
    public LakeOdsTableMapping queryByBindingIdAndTargetTable(
            Long odsDatabaseBindingId, String targetTableName) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .eq(LakeOdsTableMapping::getTargetTableName, targetTableName));
    }

    @Override
    public LakeOdsTableMapping queryByBindingIdAndSourceObject(
            Long odsDatabaseBindingId, Long sourceObjectRefId) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .eq(LakeOdsTableMapping::getSourceObjectRefId, sourceObjectRefId));
    }

    @Override
    public boolean updateIfTokenAndVersion(
            LakeOdsTableMapping entity, String operationToken, Integer lockVersion) {
        if (entity == null || entity.getId() == null || operationToken == null || lockVersion == null) {
            return false;
        }
        return mapper.update(entity, new LambdaUpdateWrapper<LakeOdsTableMapping>()
                .eq(LakeOdsTableMapping::getId, entity.getId())
                .eq(LakeOdsTableMapping::getOperationToken, operationToken)
                .eq(LakeOdsTableMapping::getLockVersion, lockVersion)) > 0;
    }
}
