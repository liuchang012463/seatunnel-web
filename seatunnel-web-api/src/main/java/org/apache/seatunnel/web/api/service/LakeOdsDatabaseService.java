package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.LakeOdsDatabaseCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakePhysicalDataSourcePageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeOdsDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalDataSourceVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalSummaryVO;

import java.util.List;

public interface LakeOdsDatabaseService {

    PaginationResult<LakePhysicalDataSourceVO> page(LakePhysicalDataSourcePageDTO request);

    LakePhysicalSummaryVO summary();

    LakePhysicalDataSourceVO sourceDetail(Long sourceDataSourceId);

    /** Return only the READY ODS database choices for one source data source. */
    List<LakeOdsDatabaseVO> readyDatabases(Long sourceDataSourceId);

    LakeOdsDatabaseVO create(Long sourceDataSourceId, LakeOdsDatabaseCreateDTO request);

    LakeOdsDatabaseVO detail(Long id);

    LakeOdsDatabaseVO retry(Long id);

    LakeOdsDatabaseVO reconcile(Long id);

    void delete(Long id);
}
