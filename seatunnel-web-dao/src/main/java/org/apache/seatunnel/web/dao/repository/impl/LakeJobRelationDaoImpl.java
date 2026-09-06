package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.mapper.LakeJobRelationMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

@Repository
public class LakeJobRelationDaoImpl extends BaseDao<LakeJobRelation, LakeJobRelationMapper>
        implements LakeJobRelationDao {

    private final LakeJobRelationMapper mapper;

    public LakeJobRelationDaoImpl(@NonNull LakeJobRelationMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public List<LakeJobRelation> queryByOdsDatabaseBindingId(Long odsDatabaseBindingId) {
        if (odsDatabaseBindingId == null) {
            return Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<LakeJobRelation>()
                .eq(LakeJobRelation::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .orderByDesc(LakeJobRelation::getUpdateTime));
    }

    @Override
    public List<LakeJobRelation> queryActiveByJobId(Long jobId) {
        if (jobId == null) {
            return Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<LakeJobRelation>()
                .eq(LakeJobRelation::getJobId, jobId)
                .eq(LakeJobRelation::getRelationStatus, LakeRelationStatus.ACTIVE));
    }

    @Override
    public LakeJobRelation queryByBindingJobAndScope(
            Long odsDatabaseBindingId, Long jobId, LakeRelationScope relationScope) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeJobRelation>()
                .eq(LakeJobRelation::getOdsDatabaseBindingId, odsDatabaseBindingId)
                .eq(LakeJobRelation::getJobId, jobId)
                .eq(LakeJobRelation::getRelationScope, relationScope));
    }

    @Override
    public boolean markStaleByJobId(Long jobId) {
        if (jobId == null) {
            return false;
        }
        return mapper.update(null, new LambdaUpdateWrapper<LakeJobRelation>()
                .eq(LakeJobRelation::getJobId, jobId)
                .eq(LakeJobRelation::getRelationStatus, LakeRelationStatus.ACTIVE)
                .set(LakeJobRelation::getRelationStatus, LakeRelationStatus.STALE)) > 0;
    }
}
