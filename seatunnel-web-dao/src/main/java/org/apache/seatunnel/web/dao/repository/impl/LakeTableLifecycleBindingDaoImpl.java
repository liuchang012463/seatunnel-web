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
        if (entity == null || entity.getId() == null || operationToken == null || lockVersion == null) {
            return false;
        }
        return mapper.update(entity, new LambdaUpdateWrapper<LakeTableLifecycleBinding>()
                .eq(LakeTableLifecycleBinding::getId, entity.getId())
                .eq(LakeTableLifecycleBinding::getOperationToken, operationToken)
                .eq(LakeTableLifecycleBinding::getLockVersion, lockVersion)) > 0;
    }
}
