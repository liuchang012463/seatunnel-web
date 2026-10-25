package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;

public interface OfflineImportTaskDao extends IDao<OfflineImportTask> {

    IPage<OfflineImportTask> queryPage(OfflineImportTaskQueryDTO queryDTO);

    /** Flips the task to RUNNING only when it is still re-executable, so double submits cannot race. */
    boolean markRunningIfExecutable(Long id, java.util.Date updateTime);

    /** Fails every RUNNING task; used at startup to clear tasks orphaned by a restart. */
    int failAllRunning(String errorMessage);

    /** Counter refresh that never touches a task whose run already finished. */
    boolean updateProgressIfRunning(Long id, int total, int executed, int failed);
}
