package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.mapper.DataSourceMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public class DataSourceDaoImpl extends BaseDao<DataSource, DataSourceMapper> implements DataSourceDao {

    @Resource
    private DataSourceMapper dataSourceMapper;

    public DataSourceDaoImpl(@NonNull DataSourceMapper dataSourceMapper) {
        super(dataSourceMapper);
    }

    @Override
    public boolean checkName(String name) {
        LambdaQueryWrapper<DataSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataSource::getName, name.trim());
        return dataSourceMapper.selectCount(wrapper) > 0;
    }

    @Override
    public boolean checkNameExcludeId(String name, Long id) {
        LambdaQueryWrapper<DataSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataSource::getName, name.trim())
                .ne(id != null, DataSource::getId, id);
        return dataSourceMapper.selectCount(wrapper) > 0;
    }

    @Override
    public IPage<DataSource> queryPage(DataSourceDTO dto) {
        return queryPage(dto, null);
    }

    @Override
    public IPage<DataSource> queryPage(DataSourceDTO dto, Collection<Long> businessSystemIds) {
        LambdaQueryWrapper<DataSource> wrapper = buildQueryWrapper(dto, businessSystemIds);

        IPage<DataSource> page = new Page<>(dto.getPageNo(), dto.getPageSize());
        return dataSourceMapper.selectPage(page, wrapper);
    }

    @Override
    public IPage<DataSource> queryPageByLakeResourceStatus(
            DataSourceDTO dto, LakeResourceStatus resourceStatus) {
        IPage<DataSource> page = new Page<>(dto.getPageNo(), dto.getPageSize());
        return dataSourceMapper.selectPageByLakeResourceStatus(
                page, dto.getName(), resourceStatus == null ? null : resourceStatus.getCode());
    }

    static LambdaQueryWrapper<DataSource> buildQueryWrapper(DataSourceDTO dto) {
        return buildQueryWrapper(dto, null);
    }

    static LambdaQueryWrapper<DataSource> buildQueryWrapper(
            DataSourceDTO dto, Collection<Long> businessSystemIds) {
        boolean hasSystemIds = businessSystemIds != null;
        return new LambdaQueryWrapper<DataSource>()
                .like(StringUtils.isNotBlank(dto.getName()), DataSource::getName,
                        StringUtils.trimToEmpty(dto.getName()))
                .in(dto.getDbTypes() != null && !dto.getDbTypes().isEmpty(),
                        DataSource::getDbType, dto.getDbTypes())
                .eq((dto.getDbTypes() == null || dto.getDbTypes().isEmpty())
                                && dto.getDbType() != null,
                        DataSource::getDbType, dto.getDbType())
                .eq(StringUtils.isNotBlank(dto.getDataSourceUnit()),
                        DataSource::getDataSourceUnit, StringUtils.trimToEmpty(dto.getDataSourceUnit()))
                .eq(dto.getBusinessSystemId() != null,
                        DataSource::getBusinessSystemId, dto.getBusinessSystemId())
                .in(hasSystemIds && !businessSystemIds.isEmpty(),
                        DataSource::getBusinessSystemId, businessSystemIds)
                .eq(hasSystemIds && businessSystemIds.isEmpty(), DataSource::getId, -1L)
                .eq(dto.getStatus() != null, DataSource::getStatus, dto.getStatus())
                .eq(dto.getEnvironment() != null, DataSource::getEnvironment, dto.getEnvironment())
                .orderByDesc(DataSource::getCreateTime);
    }

    @Override
    public List<DataSource> queryByDbType(String dbType) {
        LambdaQueryWrapper<DataSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(dbType), DataSource::getDbType, dbType);
        return dataSourceMapper.selectList(wrapper);
    }

    @Override
    public List<String> queryDataSourceUnits() {
        return dataSourceMapper.selectDataSourceUnits();
    }

    @Override
    public int updateConnStatus(Long id, ConnStatus status) {
        DataSource entity = new DataSource();
        entity.setId(id);
        entity.setConnStatus(status);
        return dataSourceMapper.updateById(entity);
    }

    @Override
    public boolean existsByBusinessSystemId(Long businessSystemId) {
        return dataSourceMapper.selectCount(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getBusinessSystemId, businessSystemId)) > 0;
    }
}
