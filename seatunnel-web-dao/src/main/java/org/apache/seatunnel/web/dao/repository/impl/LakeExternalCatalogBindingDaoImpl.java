package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
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
        if (sourceDataSourceId == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getSourceDataSourceId, sourceDataSourceId)
                .eq(LakeExternalCatalogBinding::getDeleted, false));
    }

    @Override
    public LakeExternalCatalogBinding queryBySourceDataSourceIdIncludingDeleted(
            Long sourceDataSourceId) {
        return sourceDataSourceId == null ? null : mapper.selectOne(
                new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                        .eq(LakeExternalCatalogBinding::getSourceDataSourceId, sourceDataSourceId));
    }

    @Override
    public LakeExternalCatalogBinding queryByLakeDataSourceIdAndCatalogName(
            Long lakeDataSourceId, String catalogName) {
        if (lakeDataSourceId == null || catalogName == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getLakeDataSourceId, lakeDataSourceId)
                .eq(LakeExternalCatalogBinding::getCatalogName, catalogName)
                .eq(LakeExternalCatalogBinding::getDeleted, false));
    }

    @Override
    public LakeExternalCatalogBinding queryByLakeDataSourceIdAndCatalogNameIncludingDeleted(
            Long lakeDataSourceId, String catalogName) {
        return lakeDataSourceId == null || catalogName == null ? null : mapper.selectOne(
                new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                        .eq(LakeExternalCatalogBinding::getLakeDataSourceId, lakeDataSourceId)
                        .eq(LakeExternalCatalogBinding::getCatalogName, catalogName));
    }

    @Override
    public IPage<LakeExternalCatalogBinding> queryActivePage(
            IPage<LakeExternalCatalogBinding> page,
            Long lakeDataSourceId,
            Long sourceDataSourceId,
            String catalogName,
            String adapter,
            String resourceStatus,
            String validationStatus) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        LambdaQueryWrapper<LakeExternalCatalogBinding> wrapper =
                new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                        .eq(LakeExternalCatalogBinding::getDeleted, false);
        if (lakeDataSourceId != null) {
            wrapper.eq(LakeExternalCatalogBinding::getLakeDataSourceId, lakeDataSourceId);
        }
        if (sourceDataSourceId != null) {
            wrapper.eq(LakeExternalCatalogBinding::getSourceDataSourceId, sourceDataSourceId);
        }
        if (catalogName != null && !catalogName.isBlank()) {
            wrapper.like(LakeExternalCatalogBinding::getCatalogName, catalogName.trim());
        }
        if (adapter != null && !adapter.isBlank()) {
            wrapper.eq(LakeExternalCatalogBinding::getAdapter, adapter.trim());
        }
        if (resourceStatus != null && !resourceStatus.isBlank()) {
            wrapper.eq(LakeExternalCatalogBinding::getResourceStatus, resourceStatus.trim());
        }
        if (validationStatus != null && !validationStatus.isBlank()) {
            wrapper.eq(LakeExternalCatalogBinding::getValidationStatus, validationStatus.trim());
        }
        wrapper.orderByDesc(LakeExternalCatalogBinding::getUpdateTime)
                .orderByDesc(LakeExternalCatalogBinding::getId);
        return mapper.selectPage(page, wrapper);
    }

    @Override
    public boolean existsActiveBySourceDataSourceId(Long sourceDataSourceId) {
        return sourceDataSourceId != null
                && mapper.selectCount(new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getSourceDataSourceId, sourceDataSourceId)
                .eq(LakeExternalCatalogBinding::getDeleted, false)) > 0;
    }

    @Override
    public boolean existsActiveByTarget(Long lakeDataSourceId, String catalogName) {
        return lakeDataSourceId != null && catalogName != null
                && mapper.selectCount(new LambdaQueryWrapper<LakeExternalCatalogBinding>()
                .eq(LakeExternalCatalogBinding::getLakeDataSourceId, lakeDataSourceId)
                .eq(LakeExternalCatalogBinding::getCatalogName, catalogName)
                .eq(LakeExternalCatalogBinding::getDeleted, false)) > 0;
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
