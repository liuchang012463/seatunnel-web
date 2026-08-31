package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.LakeOdsDatabaseCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakePhysicalDataSourcePageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeOdsDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalDataSourceVO;

public interface LakeOdsDatabaseService {

    PaginationResult<LakePhysicalDataSourceVO> page(LakePhysicalDataSourcePageDTO request);

    LakePhysicalDataSourceVO sourceDetail(Long sourceDataSourceId);

    LakeOdsDatabaseVO create(Long sourceDataSourceId, LakeOdsDatabaseCreateDTO request);

    LakeOdsDatabaseVO detail(Long id);

    LakeOdsDatabaseVO retry(Long id);

    LakeOdsDatabaseVO reconcile(Long id);

    void delete(Long id);
}
