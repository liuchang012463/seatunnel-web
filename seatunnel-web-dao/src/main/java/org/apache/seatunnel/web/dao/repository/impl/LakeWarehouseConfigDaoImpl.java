package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeWarehouseConfig;
import org.apache.seatunnel.web.dao.mapper.LakeWarehouseConfigMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeWarehouseConfigDao;
import org.springframework.stereotype.Repository;

@Repository
public class LakeWarehouseConfigDaoImpl
        extends BaseDao<LakeWarehouseConfig, LakeWarehouseConfigMapper>
        implements LakeWarehouseConfigDao {

    private final LakeWarehouseConfigMapper mapper;

    public LakeWarehouseConfigDaoImpl(@NonNull LakeWarehouseConfigMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeWarehouseConfig querySingleton() {
        return mapper.selectOne(new LambdaQueryWrapper<LakeWarehouseConfig>()
                .eq(LakeWarehouseConfig::getConfigKey, "ODS_DORIS"));
    }

    @Override
    public int updateSingleton(LakeWarehouseConfig config) {
        return mapper.updateById(config);
    }
}
