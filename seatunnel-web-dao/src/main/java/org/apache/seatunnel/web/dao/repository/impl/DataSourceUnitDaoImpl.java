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
                .eq(DataSourceUnit::getUnitCode, unitCode)
                .ne(excludeId != null, DataSourceUnit::getId, excludeId)) > 0;
    }

    @Override
    public boolean checkName(String unitName, Long excludeId) {
        return dataSourceUnitMapper.selectCount(new LambdaQueryWrapper<DataSourceUnit>()
                .eq(DataSourceUnit::getUnitName, unitName)
                .ne(excludeId != null, DataSourceUnit::getId, excludeId)) > 0;
    }

    @Override
    public IPage<DataSourceUnit> queryPage(DataSourceUnitDTO dto) {
        return dataSourceUnitMapper.selectPage(new Page<>(dto.getPageNo(), dto.getPageSize()), buildQueryWrapper(dto));
    }

    static LambdaQueryWrapper<DataSourceUnit> buildQueryWrapper(DataSourceUnitDTO dto) {
        return new LambdaQueryWrapper<DataSourceUnit>()
                .like(StringUtils.isNotBlank(dto.getUnitCode()), DataSourceUnit::getUnitCode,
                        StringUtils.trimToEmpty(dto.getUnitCode()))
                .like(StringUtils.isNotBlank(dto.getUnitName()), DataSourceUnit::getUnitName,
                        StringUtils.trimToEmpty(dto.getUnitName()))
                .eq(dto.getStatus() != null, DataSourceUnit::getStatus, dto.getStatus())
                .orderByDesc(DataSourceUnit::getCreateTime);
    }

    @Override
    public List<DataSourceUnit> queryActive() {
        return dataSourceUnitMapper.selectList(new LambdaQueryWrapper<DataSourceUnit>()
                .eq(DataSourceUnit::getStatus, 1)
                .orderByAsc(DataSourceUnit::getUnitName));
    }
}
