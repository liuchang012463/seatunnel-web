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
    public LakeExternalCatalogBinding queryActiveById(Long id) {
        if (id == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getId, id)
                .eq(LakeExternalCatalogBinding::getDeleted, false));
    }

    @Override
    public LakeExternalCatalogBinding queryByIdIncludingDeleted(Long id) {
        return id == null ? null : mapper.selectById(id);
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
        return updateIfTokenAndVersion(entity, operationToken, lockVersion, true);
    }

    @Override
    public boolean updateIfTokenAndVersionIncludingDeleted(
            LakeExternalCatalogBinding entity, String operationToken, Integer lockVersion) {
        return updateIfTokenAndVersion(entity, operationToken, lockVersion, false);
    }

    private boolean updateIfTokenAndVersion(
            LakeExternalCatalogBinding entity, String operationToken, Integer lockVersion, boolean activeOnly) {
        if (entity == null || entity.getId() == null || lockVersion == null) {
            return false;
        }
        entity.setLockVersion(lockVersion + 1);
        LambdaUpdateWrapper<LakeExternalCatalogBinding> wrapper = new LambdaUpdateWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getId, entity.getId())
                .eq(LakeExternalCatalogBinding::getLockVersion, lockVersion);
        if (activeOnly) {
            wrapper.eq(LakeExternalCatalogBinding::getDeleted, false);
        }
        if (operationToken == null) {
            wrapper.isNull(LakeExternalCatalogBinding::getOperationToken);
        } else {
            wrapper.eq(LakeExternalCatalogBinding::getOperationToken, operationToken);
        }
        return mapper.update(entity, wrapper) > 0;
    }
}
