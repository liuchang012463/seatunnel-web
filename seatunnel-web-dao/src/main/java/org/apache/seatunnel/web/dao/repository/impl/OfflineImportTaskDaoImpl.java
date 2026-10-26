package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.dao.mapper.OfflineImportTaskMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskDao;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.Locale;

@Repository
public class OfflineImportTaskDaoImpl extends BaseDao<OfflineImportTask, OfflineImportTaskMapper>
        implements OfflineImportTaskDao {

    public OfflineImportTaskDaoImpl(@NonNull OfflineImportTaskMapper mapper) {
        super(mapper);
    }

    @Override
    public IPage<OfflineImportTask> queryPage(OfflineImportTaskQueryDTO queryDTO, Integer ownerId) {
        OfflineImportTaskQueryDTO dto = queryDTO == null
                ? new OfflineImportTaskQueryDTO() : queryDTO;
        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0
                ? 10 : Math.min(dto.getPageSize(), 100);
        LambdaQueryWrapper<OfflineImportTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OfflineImportTask::getCreateUserId, ownerId);
        if (StringUtils.isNotBlank(dto.getName())) {
            wrapper.like(OfflineImportTask::getName, dto.getName().trim());
        }
        if (StringUtils.isNotBlank(dto.getImportType())) {
            wrapper.eq(OfflineImportTask::getImportType,
                    dto.getImportType().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.isNotBlank(dto.getStatus())) {
            wrapper.eq(OfflineImportTask::getStatus,
                    dto.getStatus().trim().toUpperCase(Locale.ROOT));
        }
        wrapper.orderByDesc(OfflineImportTask::getCreateTime);
        return mybatisMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
    }

    @Override
    public boolean markQueuedIfExecutable(Long id, Date updateTime) {
        LambdaUpdateWrapper<OfflineImportTask> wrapper = new LambdaUpdateWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getId, id)
                .in(OfflineImportTask::getStatus, "PENDING", "SUCCESS", "FAILED")
                .set(OfflineImportTask::getStatus, "QUEUED")
                .set(OfflineImportTask::getUpdateTime, updateTime)
                .set(OfflineImportTask::getErrorMessage, null)
                .set(OfflineImportTask::getExecutionLog, null)
                .set(OfflineImportTask::getTotalStatements, 0)
                .set(OfflineImportTask::getExecutedStatements, 0)
                .set(OfflineImportTask::getFailedStatements, 0)
                .set(OfflineImportTask::getStartTime, null)
                .set(OfflineImportTask::getEndTime, null);
        return mybatisMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean markRunningFromQueued(Long id, Date updateTime) {
        LambdaUpdateWrapper<OfflineImportTask> wrapper = new LambdaUpdateWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getId, id)
                .eq(OfflineImportTask::getStatus, "QUEUED")
                .set(OfflineImportTask::getStatus, "RUNNING")
                .set(OfflineImportTask::getUpdateTime, updateTime)
                .set(OfflineImportTask::getStartTime, updateTime)
                .set(OfflineImportTask::getEndTime, null);
        return mybatisMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean touchActive(Long id, Date updateTime) {
        LambdaUpdateWrapper<OfflineImportTask> wrapper = new LambdaUpdateWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getId, id)
                .in(OfflineImportTask::getStatus, "RUNNING", "QUEUED")
                .set(OfflineImportTask::getUpdateTime, updateTime);
        return mybatisMapper.update(null, wrapper) > 0;
    }

    @Override
    public int failStaleActive(Date cutoff, String errorMessage) {
        Date now = new Date();
        LambdaUpdateWrapper<OfflineImportTask> wrapper = new LambdaUpdateWrapper<OfflineImportTask>()
                .in(OfflineImportTask::getStatus, "RUNNING", "QUEUED")
                .lt(OfflineImportTask::getUpdateTime, cutoff)
                .set(OfflineImportTask::getStatus, "FAILED")
                .set(OfflineImportTask::getErrorMessage, errorMessage)
                .set(OfflineImportTask::getUpdateTime, now)
                .set(OfflineImportTask::getEndTime, now);
        return mybatisMapper.update(null, wrapper);
    }

    @Override
    public boolean updateProgressIfRunning(Long id, int total, int executed, int failed) {
        LambdaUpdateWrapper<OfflineImportTask> wrapper = new LambdaUpdateWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getId, id)
                .eq(OfflineImportTask::getStatus, "RUNNING")
                .set(OfflineImportTask::getTotalStatements, total)
                .set(OfflineImportTask::getExecutedStatements, executed)
                .set(OfflineImportTask::getFailedStatements, failed)
                .set(OfflineImportTask::getUpdateTime, new Date());
        return mybatisMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean markTerminatedIfActive(
            Long id, String status, String errorMessage, String executionLog, Date endTime) {
        LambdaUpdateWrapper<OfflineImportTask> wrapper = new LambdaUpdateWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getId, id)
                .in(OfflineImportTask::getStatus, "RUNNING", "QUEUED")
                .set(OfflineImportTask::getStatus, status)
                .set(OfflineImportTask::getErrorMessage, errorMessage)
                .set(OfflineImportTask::getExecutionLog, executionLog)
                .set(OfflineImportTask::getEndTime, endTime)
                .set(OfflineImportTask::getUpdateTime, endTime);
        return mybatisMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean deleteIfNotRunning(Long id) {
        LambdaQueryWrapper<OfflineImportTask> wrapper = new LambdaQueryWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getId, id)
                .ne(OfflineImportTask::getStatus, "RUNNING");
        return mybatisMapper.delete(wrapper) > 0;
    }

    @Override
    public boolean existsByFileResourceId(Long fileResourceId) {
        if (fileResourceId == null) {
            return false;
        }
        return mybatisMapper.selectCount(new LambdaQueryWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getFileResourceId, fileResourceId)) > 0;
    }
}
