package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.OfflineImportTaskRun;
import org.apache.seatunnel.web.dao.mapper.OfflineImportTaskRunMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskRunDao;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public class OfflineImportTaskRunDaoImpl extends BaseDao<OfflineImportTaskRun, OfflineImportTaskRunMapper>
        implements OfflineImportTaskRunDao {

    public OfflineImportTaskRunDaoImpl(@NonNull OfflineImportTaskRunMapper mapper) {
        super(mapper);
    }

    @Override
    public List<OfflineImportTaskRun> queryByTaskId(Long taskId, Integer ownerId) {
        if (taskId == null || ownerId == null) {
            return List.of();
        }
        return mybatisMapper.selectList(new LambdaQueryWrapper<OfflineImportTaskRun>()
                .eq(OfflineImportTaskRun::getTaskId, taskId)
                .eq(OfflineImportTaskRun::getCreateUserId, ownerId)
                .orderByDesc(OfflineImportTaskRun::getStartTime)
                .orderByDesc(OfflineImportTaskRun::getId));
    }

    @Override
    public boolean finishActiveRun(
            Long runId,
            Long taskId,
            String status,
            String errorMessage,
            String executionLog,
            Integer totalStatements,
            Integer executedStatements,
            Integer failedStatements,
            Date endTime) {
        return mybatisMapper.update(null, new LambdaUpdateWrapper<OfflineImportTaskRun>()
                .eq(OfflineImportTaskRun::getId, runId)
                .eq(OfflineImportTaskRun::getTaskId, taskId)
                .eq(OfflineImportTaskRun::getStatus, "RUNNING")
                .set(OfflineImportTaskRun::getStatus, status)
                .set(OfflineImportTaskRun::getErrorMessage, errorMessage)
                .set(OfflineImportTaskRun::getExecutionLog, executionLog)
                .set(OfflineImportTaskRun::getTotalStatements, totalStatements)
                .set(OfflineImportTaskRun::getExecutedStatements, executedStatements)
                .set(OfflineImportTaskRun::getFailedStatements, failedStatements)
                .set(OfflineImportTaskRun::getEndTime, endTime)
                .set(OfflineImportTaskRun::getUpdateTime, endTime)) > 0;
    }

    @Override
    public boolean touchActiveRun(Long taskId, Date updateTime) {
        return mybatisMapper.update(null, new LambdaUpdateWrapper<OfflineImportTaskRun>()
                .eq(OfflineImportTaskRun::getTaskId, taskId)
                .eq(OfflineImportTaskRun::getStatus, "RUNNING")
                .set(OfflineImportTaskRun::getUpdateTime, updateTime)) > 0;
    }

    @Override
    public int failStaleRuns(Date cutoff, String errorMessage, Date endTime) {
        return mybatisMapper.update(null, new LambdaUpdateWrapper<OfflineImportTaskRun>()
                .eq(OfflineImportTaskRun::getStatus, "RUNNING")
                .lt(OfflineImportTaskRun::getUpdateTime, cutoff)
                .set(OfflineImportTaskRun::getStatus, "FAILED")
                .set(OfflineImportTaskRun::getErrorMessage, errorMessage)
                .set(OfflineImportTaskRun::getExecutionLog, errorMessage)
                .set(OfflineImportTaskRun::getEndTime, endTime)
                .set(OfflineImportTaskRun::getUpdateTime, endTime));
    }
}
