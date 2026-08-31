package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.mapper.BusinessSystemMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.spi.bean.dto.BusinessSystemDTO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class BusinessSystemDaoImpl extends BaseDao<BusinessSystem, BusinessSystemMapper>
        implements BusinessSystemDao {

    private final BusinessSystemMapper businessSystemMapper;

    public BusinessSystemDaoImpl(@NonNull BusinessSystemMapper businessSystemMapper) {
        super(businessSystemMapper);
        this.businessSystemMapper = businessSystemMapper;
    }

    @Override
    public boolean checkCode(Long unitId, String systemCode, Long excludeId) {
        return businessSystemMapper.selectCount(new LambdaQueryWrapper<BusinessSystem>()
                .eq(BusinessSystem::getUnitId, unitId)
                .eq(BusinessSystem::getSystemCode, systemCode)
                .ne(excludeId != null, BusinessSystem::getId, excludeId)) > 0;
    }

    @Override
    public boolean checkName(Long unitId, String systemName, Long excludeId) {
        return businessSystemMapper.selectCount(new LambdaQueryWrapper<BusinessSystem>()
                .eq(BusinessSystem::getUnitId, unitId)
                .eq(BusinessSystem::getSystemName, systemName)
                .ne(excludeId != null, BusinessSystem::getId, excludeId)) > 0;
    }

    @Override
    public IPage<BusinessSystem> queryPage(BusinessSystemDTO dto) {
        return businessSystemMapper.selectPage(new Page<>(dto.getPageNo(), dto.getPageSize()), buildQueryWrapper(dto));
    }

    static LambdaQueryWrapper<BusinessSystem> buildQueryWrapper(BusinessSystemDTO dto) {
        return new LambdaQueryWrapper<BusinessSystem>()
                .eq(dto.getUnitId() != null, BusinessSystem::getUnitId, dto.getUnitId())
                .like(StringUtils.isNotBlank(dto.getSystemCode()), BusinessSystem::getSystemCode,
                        StringUtils.trimToEmpty(dto.getSystemCode()))
                .like(StringUtils.isNotBlank(dto.getSystemName()), BusinessSystem::getSystemName,
                        StringUtils.trimToEmpty(dto.getSystemName()))
                .eq(dto.getStatus() != null, BusinessSystem::getStatus, dto.getStatus())
                .orderByDesc(BusinessSystem::getCreateTime);
    }

    @Override
    public List<BusinessSystem> queryByUnitId(Long unitId) {
        return businessSystemMapper.selectList(new LambdaQueryWrapper<BusinessSystem>()
                .eq(BusinessSystem::getUnitId, unitId)
                .orderByAsc(BusinessSystem::getSystemName));
    }

    @Override
    public boolean existsByUnitId(Long unitId) {
        return businessSystemMapper.selectCount(new LambdaQueryWrapper<BusinessSystem>()
                .eq(BusinessSystem::getUnitId, unitId)) > 0;
    }
}
