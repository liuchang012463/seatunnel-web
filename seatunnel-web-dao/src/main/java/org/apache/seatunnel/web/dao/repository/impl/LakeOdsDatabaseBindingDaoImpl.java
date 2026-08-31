package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.mapper.LakeOdsDatabaseBindingMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.springframework.stereotype.Repository;

@Repository
public class LakeOdsDatabaseBindingDaoImpl extends BaseDao<LakeOdsDatabaseBinding, LakeOdsDatabaseBindingMapper>
        implements LakeOdsDatabaseBindingDao {

    private final LakeOdsDatabaseBindingMapper mapper;

    public LakeOdsDatabaseBindingDaoImpl(@NonNull LakeOdsDatabaseBindingMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeOdsDatabaseBinding queryBySourceDataSourceId(Long sourceDataSourceId) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsDatabaseBinding>()
                .eq(LakeOdsDatabaseBinding::getSourceDataSourceId, sourceDataSourceId)
                .eq(LakeOdsDatabaseBinding::getDeleted, false));
    }

    @Override
    public LakeOdsDatabaseBinding queryByLakeDataSourceIdAndDatabaseName(
            Long lakeDataSourceId, String databaseName) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeOdsDatabaseBinding>()
                .eq(LakeOdsDatabaseBinding::getLakeDataSourceId, lakeDataSourceId)
                .eq(LakeOdsDatabaseBinding::getDatabaseName, databaseName)
                .eq(LakeOdsDatabaseBinding::getDeleted, false));
    }

    @Override
    public boolean updateIfTokenAndVersion(
            LakeOdsDatabaseBinding entity, String operationToken, Integer lockVersion) {
        if (entity == null || entity.getId() == null || operationToken == null || lockVersion == null) {
            return false;
        }
        entity.setLockVersion(lockVersion + 1);
        return mapper.update(entity, new LambdaUpdateWrapper<LakeOdsDatabaseBinding>()
                .eq(LakeOdsDatabaseBinding::getId, entity.getId())
                .eq(LakeOdsDatabaseBinding::getOperationToken, operationToken)
                .eq(LakeOdsDatabaseBinding::getLockVersion, lockVersion)
                .eq(LakeOdsDatabaseBinding::getDeleted, false)) > 0;
    }
}
