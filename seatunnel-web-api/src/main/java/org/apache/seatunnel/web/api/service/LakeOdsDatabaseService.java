package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.LakeOdsDatabaseCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakePhysicalDataSourcePageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeOdsDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalDataSourceVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalSummaryVO;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface LakeOdsDatabaseService {

    PaginationResult<LakePhysicalDataSourceVO> page(LakePhysicalDataSourcePageDTO request);

    LakePhysicalSummaryVO summary();

    LakePhysicalDataSourceVO sourceDetail(Long sourceDataSourceId);

    /** Return only the READY ODS database choices for one source data source. */
    List<LakeOdsDatabaseVO> readyDatabases(Long sourceDataSourceId);

    /** Returns display names keyed by the actual Doris database names in the directory. */
    Map<String, String> catalogDatabaseLabels(Long lakeDataSourceId, Set<String> databaseNames);

    LakeOdsDatabaseVO create(Long sourceDataSourceId, LakeOdsDatabaseCreateDTO request);

    LakeOdsDatabaseVO detail(Long id);

    LakeOdsDatabaseVO retry(Long id);

    LakeOdsDatabaseVO reconcile(Long id);

    void delete(Long id);
}
