package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.mapper.LakeTableLifecycleBindingMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.springframework.stereotype.Repository;

@Repository
public class LakeTableLifecycleBindingDaoImpl
        extends BaseDao<LakeTableLifecycleBinding, LakeTableLifecycleBindingMapper>
        implements LakeTableLifecycleBindingDao {

    private final LakeTableLifecycleBindingMapper mapper;

    public LakeTableLifecycleBindingDaoImpl(@NonNull LakeTableLifecycleBindingMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeTableLifecycleBinding queryByTableMappingId(Long tableMappingId) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeTableLifecycleBinding>()
                .eq(LakeTableLifecycleBinding::getTableMappingId, tableMappingId));
    }

    @Override
    public boolean updateIfTokenAndVersion(
            LakeTableLifecycleBinding entity, String operationToken, Integer lockVersion) {
        if (entity == null || entity.getId() == null || lockVersion == null) {
            return false;
        }
        entity.setLockVersion(lockVersion + 1);
        LambdaUpdateWrapper<LakeTableLifecycleBinding> wrapper = new LambdaUpdateWrapper<LakeTableLifecycleBinding>()
                .eq(LakeTableLifecycleBinding::getId, entity.getId())
                .eq(LakeTableLifecycleBinding::getLockVersion, lockVersion);
        if (operationToken == null) {
            wrapper.isNull(LakeTableLifecycleBinding::getOperationToken);
        } else {
            wrapper.eq(LakeTableLifecycleBinding::getOperationToken, operationToken);
        }
        return mapper.update(entity, wrapper) > 0;
    }
}
