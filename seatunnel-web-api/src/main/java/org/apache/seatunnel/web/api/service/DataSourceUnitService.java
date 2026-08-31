package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.DataSourceUnitDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceUnitVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.List;

/** Application service for data source owning-unit master data. */
public interface DataSourceUnitService {

    Long create(DataSourceUnitDTO dto);

    Boolean update(Long id, DataSourceUnitDTO dto);

    DataSourceUnitVO getById(Long id);

    PaginationResult<DataSourceUnitVO> pageQuery(DataSourceUnitDTO dto);

    void delete(Long id);

    List<DataSourceUnitVO> listActive();

    List<OptionVO> options();
}
