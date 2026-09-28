package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.mapper.DataSourceUnitMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceUnitDTO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DataSourceUnitDaoImpl extends BaseDao<DataSourceUnit, DataSourceUnitMapper>
        implements DataSourceUnitDao {

    private final DataSourceUnitMapper dataSourceUnitMapper;

    public DataSourceUnitDaoImpl(@NonNull DataSourceUnitMapper dataSourceUnitMapper) {
        super(dataSourceUnitMapper);
        this.dataSourceUnitMapper = dataSourceUnitMapper;
    }

    @Override
    public boolean checkCode(String unitCode, Long excludeId) {
        return dataSourceUnitMapper.selectCount(new LambdaQueryWrapper<DataSourceUnit>()
                .eq(MyBatisColumn.getter(DataSourceUnit::getUnitCode), unitCode)
                .ne(excludeId != null, MyBatisColumn.getter(DataSourceUnit::getId), excludeId)) > 0;
    }

    @Override
    public boolean checkName(String unitName, Long excludeId) {
        return dataSourceUnitMapper.selectCount(new LambdaQueryWrapper<DataSourceUnit>()
                .eq(MyBatisColumn.getter(DataSourceUnit::getUnitName), unitName)
                .ne(excludeId != null, MyBatisColumn.getter(DataSourceUnit::getId), excludeId)) > 0;
    }

    @Override
    public IPage<DataSourceUnit> queryPage(DataSourceUnitDTO dto) {
        return dataSourceUnitMapper.selectPage(new Page<>(dto.getPageNo(), dto.getPageSize()), buildQueryWrapper(dto));
    }

    static LambdaQueryWrapper<DataSourceUnit> buildQueryWrapper(DataSourceUnitDTO dto) {
        return new LambdaQueryWrapper<DataSourceUnit>()
                .like(StringUtils.isNotBlank(dto.getUnitCode()), MyBatisColumn.getter(DataSourceUnit::getUnitCode),
                        StringUtils.trimToEmpty(dto.getUnitCode()))
                .like(StringUtils.isNotBlank(dto.getUnitName()), MyBatisColumn.getter(DataSourceUnit::getUnitName),
                        StringUtils.trimToEmpty(dto.getUnitName()))
                .eq(dto.getStatus() != null, MyBatisColumn.getter(DataSourceUnit::getStatus), dto.getStatus())
                .orderByDesc(MyBatisColumn.getter(DataSourceUnit::getCreateTime));
    }

    @Override
    public List<DataSourceUnit> queryActive() {
        return dataSourceUnitMapper.selectList(new LambdaQueryWrapper<DataSourceUnit>()
                .eq(MyBatisColumn.getter(DataSourceUnit::getStatus), 1)
                .orderByAsc(MyBatisColumn.getter(DataSourceUnit::getUnitName)));
    }
}
