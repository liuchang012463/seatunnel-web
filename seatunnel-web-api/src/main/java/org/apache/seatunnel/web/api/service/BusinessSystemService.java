package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.BusinessSystemDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.BusinessSystemVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.List;

/** Application service for business-system master data. */
public interface BusinessSystemService {

    Long create(BusinessSystemDTO dto);

    Boolean update(Long id, BusinessSystemDTO dto);

    BusinessSystemVO getById(Long id);

    PaginationResult<BusinessSystemVO> pageQuery(BusinessSystemDTO dto);

    void delete(Long id);

    /** Returns active systems of an active unit for selection controls. */
    List<BusinessSystemVO> listByUnitId(Long unitId);

    List<OptionVO> options(Long unitId);
}
