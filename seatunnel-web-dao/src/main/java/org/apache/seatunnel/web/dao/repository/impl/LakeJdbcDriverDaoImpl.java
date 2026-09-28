package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeJdbcDriver;
import org.apache.seatunnel.web.dao.mapper.LakeJdbcDriverMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeJdbcDriverDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LakeJdbcDriverDaoImpl
        extends BaseDao<LakeJdbcDriver, LakeJdbcDriverMapper>
        implements LakeJdbcDriverDao {

    private final LakeJdbcDriverMapper mapper;

    public LakeJdbcDriverDaoImpl(@NonNull LakeJdbcDriverMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeJdbcDriver queryByAdapter(String adapter) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeJdbcDriver>()
                .eq(MyBatisColumn.getter(LakeJdbcDriver::getAdapter), adapter));
    }

    @Override
    public List<LakeJdbcDriver> queryEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<LakeJdbcDriver>()
                .eq(MyBatisColumn.getter(LakeJdbcDriver::getEnabled), true)
                .orderByAsc(MyBatisColumn.getter(LakeJdbcDriver::getAdapter))
                .orderByDesc(MyBatisColumn.getter(LakeJdbcDriver::getUpdateTime)));
    }

    @Override
    public int updateDriver(LakeJdbcDriver driver) {
        return mapper.updateById(driver);
    }
}
