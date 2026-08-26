package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.mapper.MetadataSourceBindingMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public class MetadataBindingDaoImpl extends BaseDao<MetadataSourceBinding, MetadataSourceBindingMapper>
        implements MetadataBindingDao {

    private final MetadataSourceBindingMapper metadataSourceBindingMapper;

    public MetadataBindingDaoImpl(@NonNull MetadataSourceBindingMapper metadataSourceBindingMapper) {
        super(metadataSourceBindingMapper);
        this.metadataSourceBindingMapper = metadataSourceBindingMapper;
    }

    @Override
    public MetadataSourceBinding queryByDataSourceId(Long dataSourceId) {
        return metadataSourceBindingMapper.selectOne(new LambdaQueryWrapper<MetadataSourceBinding>()
                .eq(MetadataSourceBinding::getDataSourceId, dataSourceId));
    }

    @Override
    public int deleteByDataSourceId(Long dataSourceId) {
        return metadataSourceBindingMapper.delete(new LambdaQueryWrapper<MetadataSourceBinding>()
                .eq(MetadataSourceBinding::getDataSourceId, dataSourceId));
    }

    @Override
    public List<MetadataSourceBinding> queryReconcileCandidates(Date now, Date staleClaimBefore, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return metadataSourceBindingMapper.selectList(new LambdaQueryWrapper<MetadataSourceBinding>()
                .in(MetadataSourceBinding::getDesiredState, MetadataDesiredState.ACTIVE, MetadataDesiredState.DELETED)
                .and(wrapper -> wrapper.in(MetadataSourceBinding::getSyncStatus,
                                MetadataSyncStatus.PENDING,
                                MetadataSyncStatus.ERROR,
                                MetadataSyncStatus.DELETING,
                                MetadataSyncStatus.WAITING)
                        .or(stale -> stale.eq(MetadataSourceBinding::getSyncStatus, MetadataSyncStatus.SYNCING)
                                .le(MetadataSourceBinding::getUpdateTime, staleClaimBefore)))
                .and(wrapper -> wrapper.ne(MetadataSourceBinding::getSyncStatus, MetadataSyncStatus.ERROR)
                        .or()
                        .isNotNull(MetadataSourceBinding::getNextRetryTime)
                        .le(MetadataSourceBinding::getNextRetryTime, now))
                .orderByAsc(MetadataSourceBinding::getNextRetryTime)
                .last("LIMIT " + safeLimit));
    }

    @Override
    public boolean tryClaim(Long id, Long expectedVersion, Date now, Date staleClaimBefore) {
        if (id == null || expectedVersion == null) {
            return false;
        }
        return metadataSourceBindingMapper.update(null, new LambdaUpdateWrapper<MetadataSourceBinding>()
                .eq(MetadataSourceBinding::getId, id)
                .eq(MetadataSourceBinding::getVersion, expectedVersion)
                .and(wrapper -> wrapper.in(MetadataSourceBinding::getSyncStatus,
                                MetadataSyncStatus.PENDING,
                                MetadataSyncStatus.ERROR,
                                MetadataSyncStatus.DELETING,
                                MetadataSyncStatus.WAITING)
                        .or(stale -> stale.eq(MetadataSourceBinding::getSyncStatus, MetadataSyncStatus.SYNCING)
                                .le(MetadataSourceBinding::getUpdateTime, staleClaimBefore)))
                .and(wrapper -> wrapper.ne(MetadataSourceBinding::getSyncStatus, MetadataSyncStatus.ERROR)
                        .or(retry -> retry.isNotNull(MetadataSourceBinding::getNextRetryTime)
                                .le(MetadataSourceBinding::getNextRetryTime, now)))
                .set(MetadataSourceBinding::getSyncStatus, MetadataSyncStatus.SYNCING)
                .set(MetadataSourceBinding::getVersion, expectedVersion + 1L)
                .set(MetadataSourceBinding::getUpdateTime, now)) > 0;
    }

    @Override
    public boolean updateClaimed(MetadataSourceBinding binding, Long expectedVersion) {
        if (binding == null || binding.getId() == null || expectedVersion == null) {
            return false;
        }
        return metadataSourceBindingMapper.update(binding, new LambdaUpdateWrapper<MetadataSourceBinding>()
                .eq(MetadataSourceBinding::getId, binding.getId())
                .eq(MetadataSourceBinding::getVersion, expectedVersion)
                .eq(MetadataSourceBinding::getSyncStatus, MetadataSyncStatus.SYNCING)) > 0;
    }

    @Override
    public boolean deleteClaimed(Long id, Long expectedVersion) {
        if (id == null || expectedVersion == null) {
            return false;
        }
        return metadataSourceBindingMapper.delete(new LambdaQueryWrapper<MetadataSourceBinding>()
                .eq(MetadataSourceBinding::getId, id)
                .eq(MetadataSourceBinding::getVersion, expectedVersion)
                .eq(MetadataSourceBinding::getSyncStatus, MetadataSyncStatus.SYNCING)) > 0;
    }
}
