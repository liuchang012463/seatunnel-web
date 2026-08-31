package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceUnitDTO;

import java.util.List;

public interface DataSourceUnitDao extends IDao<DataSourceUnit> {

    boolean checkCode(String unitCode, Long excludeId);

    boolean checkName(String unitName, Long excludeId);

    IPage<DataSourceUnit> queryPage(DataSourceUnitDTO dto);

    List<DataSourceUnit> queryActive();
}
