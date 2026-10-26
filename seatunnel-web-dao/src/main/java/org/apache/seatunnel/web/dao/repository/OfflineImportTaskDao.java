package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;

public interface OfflineImportTaskDao extends IDao<OfflineImportTask> {

    IPage<OfflineImportTask> queryPage(OfflineImportTaskQueryDTO queryDTO);

    /** Flips the task to QUEUED only when it is still re-executable, so double submits cannot race. */
    boolean markQueuedIfExecutable(Long id, java.util.Date updateTime);

    /** The worker claims a QUEUED task as RUNNING; false means it was deleted meanwhile. */
    boolean markRunningFromQueued(Long id, java.util.Date updateTime);

    /** Fails every RUNNING/QUEUED task; used at startup to clear tasks orphaned by a restart. */
    int failAllActive(String errorMessage);

    /** Counter refresh that never touches a task whose run already finished. */
    boolean updateProgressIfRunning(Long id, int total, int executed, int failed);
}
