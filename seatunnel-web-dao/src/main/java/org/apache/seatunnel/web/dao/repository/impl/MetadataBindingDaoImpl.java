package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.mapper.MetadataSourceBindingMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.springframework.stereotype.Repository;

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
    public List<MetadataSourceBinding> queryReconcileCandidates(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return metadataSourceBindingMapper.selectList(new LambdaQueryWrapper<MetadataSourceBinding>()
                .in(MetadataSourceBinding::getDesiredState, MetadataDesiredState.ACTIVE, MetadataDesiredState.DELETED)
                .in(MetadataSourceBinding::getSyncStatus,
                        MetadataSyncStatus.PENDING,
                        MetadataSyncStatus.ERROR,
                        MetadataSyncStatus.DELETING,
                        MetadataSyncStatus.WAITING)
                .orderByAsc(MetadataSourceBinding::getNextRetryTime)
                .last("LIMIT " + safeLimit));
    }
}
