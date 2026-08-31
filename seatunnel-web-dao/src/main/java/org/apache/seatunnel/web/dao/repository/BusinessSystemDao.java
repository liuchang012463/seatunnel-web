package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.spi.bean.dto.BusinessSystemDTO;

import java.util.List;

public interface BusinessSystemDao extends IDao<BusinessSystem> {

    boolean checkCode(Long unitId, String systemCode, Long excludeId);

    boolean checkName(Long unitId, String systemName, Long excludeId);

    IPage<BusinessSystem> queryPage(BusinessSystemDTO dto);

    List<BusinessSystem> queryByUnitId(Long unitId);

    boolean existsByUnitId(Long unitId);
}
