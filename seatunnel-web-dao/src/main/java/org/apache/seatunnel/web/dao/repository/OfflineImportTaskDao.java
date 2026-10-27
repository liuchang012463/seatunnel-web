package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;

public interface OfflineImportTaskDao extends IDao<OfflineImportTask> {

    IPage<OfflineImportTask> queryPage(OfflineImportTaskQueryDTO queryDTO, Integer ownerId);

    /** Flips the task to QUEUED only when it is still re-executable, so double submits cannot race. */
    boolean markQueuedIfExecutable(Long id, Integer ownerId, java.util.Date updateTime);

    /** The worker claims a QUEUED task as RUNNING; false means it was deleted meanwhile. */
    boolean markRunningFromQueued(Long id, Integer ownerId, java.util.Date updateTime);

    /** Deletes an owned task only while it is not RUNNING. */
    boolean deleteIfNotRunning(Long id, Integer ownerId);

    /** Fails a queued task that could not be submitted to the executor. */
    boolean failQueued(Long id, Integer ownerId, String errorMessage, java.util.Date updateTime);

    /** Keeps an in-flight task alive so another instance does not reclaim it. */
    boolean touchActive(Long id, java.util.Date updateTime);

    /** Fails RUNNING/QUEUED tasks whose heartbeat is older than the cutoff. */
    int failStaleActive(java.util.Date cutoff, String errorMessage);

    /** Counter refresh that never touches a task whose run already finished. */
    boolean updateProgressIfRunning(Long id, int total, int executed, int failed);

    /** Terminal update that only applies while the task still owns the run. */
    boolean markTerminatedIfActive(
            Long id, String status, String errorMessage, String executionLog, java.util.Date endTime);

    /** Atomic delete that cannot race with the worker claiming the task as RUNNING. */
    boolean deleteIfNotRunning(Long id);

    boolean existsByFileResourceId(Long fileResourceId);
}
