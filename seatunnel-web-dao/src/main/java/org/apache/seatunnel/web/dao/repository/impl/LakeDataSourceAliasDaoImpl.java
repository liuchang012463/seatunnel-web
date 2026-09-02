package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeDataSourceAlias;
import org.apache.seatunnel.web.dao.mapper.LakeDataSourceAliasMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeDataSourceAliasDao;
import org.springframework.stereotype.Repository;

@Repository
public class LakeDataSourceAliasDaoImpl
        extends BaseDao<LakeDataSourceAlias, LakeDataSourceAliasMapper>
        implements LakeDataSourceAliasDao {

    private final LakeDataSourceAliasMapper mapper;

    public LakeDataSourceAliasDaoImpl(@NonNull LakeDataSourceAliasMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeDataSourceAlias queryByLegacyId(Long legacyDataSourceId) {
        return legacyDataSourceId == null ? null : mapper.selectOne(
                new LambdaQueryWrapper<LakeDataSourceAlias>()
                        .eq(LakeDataSourceAlias::getLegacyDataSourceId, legacyDataSourceId));
    }
}
