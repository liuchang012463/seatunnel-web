package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.BusinessSystemService;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.spi.bean.dto.BusinessSystemDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.BusinessSystemVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/** Default local implementation of business-system master-data operations. */
@Slf4j
@Service
public class BusinessSystemServiceImpl implements BusinessSystemService {

    private static final int ACTIVE = 1;
    private static final int INACTIVE = 0;

    @Resource
    private BusinessSystemDao businessSystemDao;

    @Resource
    private DataSourceUnitDao dataSourceUnitDao;

    @Resource
    private DataSourceDao dataSourceDao;

    @Resource
    private CurrentUserProvider currentUserProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(BusinessSystemDTO dto) {
        validateDto(dto);
        DataSourceUnit unit = getActiveUnit(dto.getUnitId());
        String code = dto.getSystemCode().trim();
        String name = dto.getSystemName().trim();
        try {
            if (businessSystemDao.checkCode(unit.getId(), code, null)
                    || businessSystemDao.checkName(unit.getId(), name, null)) {
                throw duplicateException();
            }

            BusinessSystem entity = new BusinessSystem();
            entity.setUnitId(unit.getId());
            entity.setSystemCode(code);
            entity.setSystemName(name);
            entity.setStatus(normalizeStatus(dto.getStatus()));
            entity.setRemark(dto.getRemark());
            entity.setCreateUserId(currentUserId());
            entity.setUpdateUserId(entity.getCreateUserId());
            entity.initInsert();
            businessSystemDao.insert(entity);
            return entity.getId();
        } catch (DuplicateKeyException e) {
            throw duplicateException();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Create business system failed, dto={}", dto, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean update(Long id, BusinessSystemDTO dto) {
        validateId(id);
        validateDto(dto);
        BusinessSystem existing = getEntityOrThrow(id);
        DataSourceUnit unit = getActiveUnit(dto.getUnitId());
        String code = dto.getSystemCode().trim();
        String name = dto.getSystemName().trim();
        try {
            if (businessSystemDao.checkCode(unit.getId(), code, id)
                    || businessSystemDao.checkName(unit.getId(), name, id)) {
                throw duplicateException();
            }

            BusinessSystem entity = new BusinessSystem();
            entity.setId(id);
            entity.setUnitId(unit.getId());
            entity.setSystemCode(code);
            entity.setSystemName(name);
            entity.setStatus(dto.getStatus() == null ? normalizeStatus(existing.getStatus()) : normalizeStatus(dto.getStatus()));
            entity.setRemark(dto.getRemark());
            entity.setCreateUserId(existing.getCreateUserId());
            entity.setUpdateUserId(currentUserId());
            entity.setCreateTime(existing.getCreateTime());
            entity.setUpdateTime(new Date());
            return businessSystemDao.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw duplicateException();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Update business system failed, id={}", id, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public BusinessSystemVO getById(Long id) {
        validateId(id);
        return toVO(getEntityOrThrow(id));
    }

    @Override
    public PaginationResult<BusinessSystemVO> pageQuery(BusinessSystemDTO dto) {
        if (dto == null) {
            dto = new BusinessSystemDTO();
        }
        normalizePage(dto);
        try {
            IPage<BusinessSystem> page = businessSystemDao.queryPage(dto);
            return PaginationResult.buildSuc(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()), page);
        } catch (Exception e) {
            log.error("Page query business systems failed, dto={}", dto, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        validateId(id);
        getEntityOrThrow(id);
        if (dataSourceDao.existsByBusinessSystemId(id)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "business system is referenced by a data source and cannot be deleted");
        }
        try {
            businessSystemDao.deleteById(id);
        } catch (Exception e) {
            log.error("Delete business system failed, id={}", id, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public List<BusinessSystemVO> listByUnitId(Long unitId) {
        DataSourceUnit unit = getActiveUnit(unitId);
        try {
            return businessSystemDao.queryByUnitId(unit.getId()).stream()
                    .filter(this::isActive)
                    .map(this::toVO)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("List business systems failed, unitId={}", unitId, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public List<OptionVO> options(Long unitId) {
        return listByUnitId(unitId).stream().map(system -> {
            OptionVO option = new OptionVO();
            option.setValue(system.getId());
            option.setLabel(system.getSystemName());
            option.setDescription(system.getSystemCode());
            return option;
        }).collect(Collectors.toList());
    }

    private BusinessSystemVO toVO(BusinessSystem entity) {
        BusinessSystemVO vo = new BusinessSystemVO();
        BeanUtils.copyProperties(entity, vo);
        DataSourceUnit unit = dataSourceUnitDao.queryById(entity.getUnitId());
        if (unit != null) {
            vo.setUnitCode(unit.getUnitCode());
            vo.setUnitName(unit.getUnitName());
        }
        return vo;
    }

    private BusinessSystem getEntityOrThrow(Long id) {
        BusinessSystem entity = businessSystemDao.queryById(id);
        if (entity == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "business system does not exist");
        }
        return entity;
    }

    private DataSourceUnit getActiveUnit(Long id) {
        validateId(id);
        DataSourceUnit unit = dataSourceUnitDao.queryById(id);
        if (unit == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "unit does not exist");
        }
        if (!isActive(unit)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "unit is inactive");
        }
        return unit;
    }

    private void validateDto(BusinessSystemDTO dto) {
        if (dto == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "businessSystemDTO");
        }
        validateId(dto.getUnitId());
        if (StringUtils.isBlank(dto.getSystemCode())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "systemCode");
        }
        if (StringUtils.isBlank(dto.getSystemName())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "systemName");
        }
        if (dto.getSystemCode().trim().length() > 128 || dto.getSystemName().trim().length() > 256) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "systemCode/systemName");
        }
        if (dto.getStatus() != null && dto.getStatus() != ACTIVE && dto.getStatus() != INACTIVE) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "status");
        }
    }

    private void normalizePage(BusinessSystemDTO dto) {
        if (dto.getPageNo() == null || dto.getPageNo() <= 0) {
            dto.setPageNo(1);
        }
        if (dto.getPageSize() == null || dto.getPageSize() <= 0) {
            dto.setPageSize(10);
        }
    }

    private Integer normalizeStatus(Integer status) {
        return status == null ? ACTIVE : status;
    }

    private boolean isActive(DataSourceUnit unit) {
        return unit.getStatus() == null || unit.getStatus() == ACTIVE;
    }

    private boolean isActive(BusinessSystem system) {
        return system.getStatus() == null || system.getStatus() == ACTIVE;
    }

    private Integer currentUserId() {
        return currentUserProvider == null ? null : currentUserProvider.getCurrentUserId();
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "id");
        }
    }

    private ServiceException duplicateException() {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                "systemCode/systemName already exists in the unit");
    }
}
