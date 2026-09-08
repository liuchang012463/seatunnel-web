package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.OpenMetadataServerConfig;
import org.apache.seatunnel.web.dao.mapper.OpenMetadataServerConfigMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.OpenMetadataServerConfigDao;
import org.springframework.stereotype.Repository;

@Repository
public class OpenMetadataServerConfigDaoImpl
        extends BaseDao<OpenMetadataServerConfig, OpenMetadataServerConfigMapper>
        implements OpenMetadataServerConfigDao {

    public static final String CONFIG_KEY = "OPENMETADATA";

    private final OpenMetadataServerConfigMapper mapper;

    public OpenMetadataServerConfigDaoImpl(@NonNull OpenMetadataServerConfigMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public OpenMetadataServerConfig querySingleton() {
        return mapper.selectOne(new LambdaQueryWrapper<OpenMetadataServerConfig>()
                .eq(OpenMetadataServerConfig::getConfigKey, CONFIG_KEY));
    }

    @Override
    public int updateSingleton(OpenMetadataServerConfig config) {
        return mapper.updateById(config);
    }
}
