package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.OfflineImportTaskRun;

import java.util.Date;
import java.util.List;

public interface OfflineImportTaskRunDao extends IDao<OfflineImportTaskRun> {

    List<OfflineImportTaskRun> queryByTaskId(Long taskId, Integer ownerId);

    boolean finishActiveRun(
            Long runId,
            Long taskId,
            String status,
            String errorMessage,
            String executionLog,
            Integer totalStatements,
            Integer executedStatements,
            Integer failedStatements,
            Date endTime);

    boolean touchActiveRun(Long taskId, Date updateTime);

    int failStaleRuns(Date cutoff, String errorMessage, Date endTime);
}
