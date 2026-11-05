package org.apache.seatunnel.web.api.service.impl;

import jakarta.annotation.Resource;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskRunDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/** Completes one task and its exact run record together so retries cannot cross-write history. */
@Service
public class OfflineImportTaskRunCompletionService {

    @Resource
    private OfflineImportTaskDao offlineImportTaskDao;

    @Resource
    private OfflineImportTaskRunDao offlineImportTaskRunDao;

    @Transactional(rollbackFor = Exception.class)
    public boolean finish(
            Long taskId,
            Long runId,
            String status,
            String errorMessage,
            String executionLog,
            Date endTime) {
        if (!offlineImportTaskDao.markTerminatedIfActive(taskId, status, errorMessage, executionLog, endTime)) {
            return false;
        }
        if (runId == null) {
            return true;
        }

        OfflineImportTask task = offlineImportTaskDao.queryById(taskId);
        if (task == null || !offlineImportTaskRunDao.finishActiveRun(
                runId,
                taskId,
                status,
                errorMessage,
                executionLog,
                task.getTotalStatements(),
                task.getExecutedStatements(),
                task.getFailedStatements(),
                endTime)) {
            throw new IllegalStateException("Offline import run record changed before task completion: " + runId);
        }
        return true;
    }
}
