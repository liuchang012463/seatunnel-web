package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.dao.entity.LakeWarehouseConfig;
import org.apache.seatunnel.web.spi.bean.dto.LakeWarehouseConfigDTO;
import org.apache.seatunnel.web.spi.bean.vo.LakeJdbcDriverVO;
import org.apache.seatunnel.web.spi.bean.vo.LakeDorisStatusVO;
import org.apache.seatunnel.web.spi.bean.vo.LakeWarehouseConfigVO;

import java.util.List;

public interface LakeWarehouseService {

    LakeWarehouseConfigVO getConfig();

    LakeWarehouseConfigVO saveConfig(LakeWarehouseConfigDTO request);

    LakeWarehouseConfigVO testConfig(LakeWarehouseConfigDTO request);

    LakeDorisStatusVO getDorisStatus();

    List<LakeJdbcDriverVO> listDrivers();

    LakeJdbcDriverVO registerDriver(String adapter, String fileName, String driverLocation,
                                    String driverClass, String sha256, String dorisMd5);

    LakeJdbcDriverVO uploadDriver(org.springframework.web.multipart.MultipartFile file,
                                  String adapter, String driverClass, boolean overwrite);

    LakeWarehouseConfig requireConfig();

    Long requireSystemDataSourceId();

    Long canonicalDataSourceId(Long lakeDataSourceId);
}
