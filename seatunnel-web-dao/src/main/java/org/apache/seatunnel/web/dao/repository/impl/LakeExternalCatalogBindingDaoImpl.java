package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;
import org.apache.seatunnel.web.dao.mapper.LakeExternalCatalogBindingMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeExternalCatalogBindingDao;
import org.springframework.stereotype.Repository;

@Repository
public class LakeExternalCatalogBindingDaoImpl
        extends BaseDao<LakeExternalCatalogBinding, LakeExternalCatalogBindingMapper>
        implements LakeExternalCatalogBindingDao {

    private final LakeExternalCatalogBindingMapper mapper;

    public LakeExternalCatalogBindingDaoImpl(@NonNull LakeExternalCatalogBindingMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeExternalCatalogBinding queryBySourceDataSourceId(Long sourceDataSourceId) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getSourceDataSourceId, sourceDataSourceId)
                .eq(LakeExternalCatalogBinding::getDeleted, false));
    }

    @Override
    public LakeExternalCatalogBinding queryByLakeDataSourceIdAndCatalogName(
            Long lakeDataSourceId, String catalogName) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getLakeDataSourceId, lakeDataSourceId)
                .eq(LakeExternalCatalogBinding::getCatalogName, catalogName)
                .eq(LakeExternalCatalogBinding::getDeleted, false));
    }

    @Override
    public boolean updateIfTokenAndVersion(
            LakeExternalCatalogBinding entity, String operationToken, Integer lockVersion) {
        if (entity == null || entity.getId() == null || operationToken == null || lockVersion == null) {
            return false;
        }
        entity.setLockVersion(lockVersion + 1);
        return mapper.update(entity, new LambdaUpdateWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getId, entity.getId())
                .eq(LakeExternalCatalogBinding::getOperationToken, operationToken)
                .eq(LakeExternalCatalogBinding::getLockVersion, lockVersion)
                .eq(LakeExternalCatalogBinding::getDeleted, false)) > 0;
    }
}
