package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineDatapackCsvSelectDTO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineDatapackCsvInspectVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineDatapackCsvSelectVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineDatapackInspectVO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskVO;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskRunVO;

import java.util.List;

/** Management and execution of DMP/SQL offline import tasks. */
public interface OfflineImportTaskService {

    OfflineImportTaskVO create(OfflineImportTaskCreateDTO dto);

    OfflineImportTaskVO update(Long id, OfflineImportTaskCreateDTO dto);

    void updateReleaseState(Long id, String releaseState);

    PaginationResult<OfflineImportTaskVO> page(OfflineImportTaskQueryDTO query);

    OfflineImportTaskVO get(Long id);

    List<OfflineImportTaskRunVO> history(Long id);

    void delete(Long id);

    /** Queues the task; the background worker claims it as RUNNING. */
    void execute(Long id);

    /** Queues one run and passes the one-time package password only to its worker. */
    void execute(Long id, char[] password);

    OfflineDatapackInspectVO inspectDatapack(Long fileResourceId, char[] password);

    OfflineDatapackCsvInspectVO inspectDatapackCsv(Long fileResourceId, char[] password);

    OfflineDatapackCsvSelectVO selectDatapackCsv(OfflineDatapackCsvSelectDTO request);
}
