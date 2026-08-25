package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.DataSourceUnitService;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceUnitDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceUnitVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/** Default local implementation of unit master-data operations. */
@Slf4j
@Service
public class DataSourceUnitServiceImpl implements DataSourceUnitService {

    private static final int ACTIVE = 1;
    private static final int INACTIVE = 0;

    @Resource
    private DataSourceUnitDao dataSourceUnitDao;

    @Resource
    private BusinessSystemDao businessSystemDao;

    @Resource
    private CurrentUserProvider currentUserProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DataSourceUnitDTO dto) {
        validateDto(dto);

        String code = dto.getUnitCode().trim();
        String name = dto.getUnitName().trim();
        try {
            if (dataSourceUnitDao.checkCode(code, null) || dataSourceUnitDao.checkName(name, null)) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "unitCode/unitName already exists");
            }

            DataSourceUnit entity = new DataSourceUnit();
            entity.setUnitCode(code);
            entity.setUnitName(name);
            entity.setStatus(normalizeStatus(dto.getStatus()));
            entity.setRemark(dto.getRemark());
            entity.setCreateUserId(currentUserId());
            entity.setUpdateUserId(entity.getCreateUserId());
            entity.initInsert();
            dataSourceUnitDao.insert(entity);
            return entity.getId();
        } catch (DuplicateKeyException e) {
            throw duplicateException();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Create data source unit failed, dto={}", dto, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean update(Long id, DataSourceUnitDTO dto) {
        validateId(id);
        validateDto(dto);

        DataSourceUnit existing = getEntityOrThrow(id);
        String code = dto.getUnitCode().trim();
        String name = dto.getUnitName().trim();
        try {
            if (dataSourceUnitDao.checkCode(code, id) || dataSourceUnitDao.checkName(name, id)) {
                throw duplicateException();
            }

            DataSourceUnit entity = new DataSourceUnit();
            entity.setId(id);
            entity.setUnitCode(code);
            entity.setUnitName(name);
            entity.setStatus(dto.getStatus() == null ? normalizeStatus(existing.getStatus()) : normalizeStatus(dto.getStatus()));
            entity.setRemark(dto.getRemark());
            entity.setCreateUserId(existing.getCreateUserId());
            entity.setUpdateUserId(currentUserId());
            entity.setCreateTime(existing.getCreateTime());
            entity.setUpdateTime(new Date());
            return dataSourceUnitDao.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw duplicateException();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Update data source unit failed, id={}", id, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public DataSourceUnitVO getById(Long id) {
        validateId(id);
        return toVO(getEntityOrThrow(id));
    }

    @Override
    public PaginationResult<DataSourceUnitVO> pageQuery(DataSourceUnitDTO dto) {
        if (dto == null) {
            dto = new DataSourceUnitDTO();
        }
        normalizePage(dto);
        try {
            IPage<DataSourceUnit> page = dataSourceUnitDao.queryPage(dto);
            return PaginationResult.buildSuc(page.getRecords().stream().map(this::toVO).collect(Collectors.toList()), page);
        } catch (Exception e) {
            log.error("Page query data source units failed, dto={}", dto, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        validateId(id);
        getEntityOrThrow(id);
        if (businessSystemDao.existsByUnitId(id)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "unit is referenced by a business system and cannot be deleted");
        }
        try {
            dataSourceUnitDao.deleteById(id);
        } catch (Exception e) {
            log.error("Delete data source unit failed, id={}", id, e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public List<DataSourceUnitVO> listActive() {
        try {
            return dataSourceUnitDao.queryActive().stream().map(this::toVO).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("List active data source units failed", e);
            throw new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, e.getMessage());
        }
    }

    @Override
    public List<OptionVO> options() {
        return listActive().stream().map(unit -> {
            OptionVO option = new OptionVO();
            option.setValue(unit.getId());
            option.setLabel(unit.getUnitName());
            option.setDescription(unit.getUnitCode());
            return option;
        }).collect(Collectors.toList());
    }

    private DataSourceUnitVO toVO(DataSourceUnit entity) {
        DataSourceUnitVO vo = new DataSourceUnitVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private DataSourceUnit getEntityOrThrow(Long id) {
        DataSourceUnit entity = dataSourceUnitDao.queryById(id);
        if (entity == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "unit does not exist");
        }
        return entity;
    }

    private void validateDto(DataSourceUnitDTO dto) {
        if (dto == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "dataSourceUnitDTO");
        }
        if (StringUtils.isBlank(dto.getUnitCode())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "unitCode");
        }
        if (StringUtils.isBlank(dto.getUnitName())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "unitName");
        }
        if (dto.getUnitCode().trim().length() > 128 || dto.getUnitName().trim().length() > 256) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "unitCode/unitName");
        }
        if (dto.getStatus() != null && dto.getStatus() != ACTIVE && dto.getStatus() != INACTIVE) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "status");
        }
    }

    private Integer normalizeStatus(Integer status) {
        return status == null ? ACTIVE : status;
    }

    private void normalizePage(DataSourceUnitDTO dto) {
        if (dto.getPageNo() == null || dto.getPageNo() <= 0) {
            dto.setPageNo(1);
        }
        if (dto.getPageSize() == null || dto.getPageSize() <= 0) {
            dto.setPageSize(10);
        }
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "id");
        }
    }

    private Integer currentUserId() {
        return currentUserProvider == null ? null : currentUserProvider.getCurrentUserId();
    }

    private ServiceException duplicateException() {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "unitCode/unitName already exists");
    }
}
