package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.OfflineImportTaskVO;

/** Management and execution of DMP/SQL offline import tasks. */
public interface OfflineImportTaskService {

    OfflineImportTaskVO create(OfflineImportTaskCreateDTO dto);

    PaginationResult<OfflineImportTaskVO> page(OfflineImportTaskQueryDTO query);

    OfflineImportTaskVO get(Long id);

    void delete(Long id);

    /** Marks the task RUNNING and executes it on the background worker. */
    void execute(Long id);
}
