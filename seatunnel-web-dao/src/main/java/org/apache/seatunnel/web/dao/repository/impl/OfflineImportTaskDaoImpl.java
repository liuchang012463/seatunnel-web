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

@Repository
public class OfflineImportTaskDaoImpl extends BaseDao<OfflineImportTask, OfflineImportTaskMapper>
        implements OfflineImportTaskDao {

    public OfflineImportTaskDaoImpl(@NonNull OfflineImportTaskMapper mapper) {
        super(mapper);
    }

    @Override
    public IPage<OfflineImportTask> queryPage(OfflineImportTaskQueryDTO queryDTO) {
        OfflineImportTaskQueryDTO dto = queryDTO == null
                ? new OfflineImportTaskQueryDTO() : queryDTO;
        int pageNo = dto.getPageNo() == null || dto.getPageNo() <= 0 ? 1 : dto.getPageNo();
        int pageSize = dto.getPageSize() == null || dto.getPageSize() <= 0
                ? 10 : Math.min(dto.getPageSize(), 100);
        LambdaQueryWrapper<OfflineImportTask> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(dto.getName())) {
            wrapper.like(OfflineImportTask::getName, dto.getName().trim());
        }
        if (StringUtils.isNotBlank(dto.getImportType())) {
            wrapper.eq(OfflineImportTask::getImportType, dto.getImportType().trim());
        }
        if (StringUtils.isNotBlank(dto.getStatus())) {
            wrapper.eq(OfflineImportTask::getStatus, dto.getStatus().trim());
        }
        wrapper.orderByDesc(OfflineImportTask::getCreateTime);
        return mybatisMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
    }

    @Override
    public boolean markRunningIfExecutable(Long id, Date updateTime) {
        LambdaUpdateWrapper<OfflineImportTask> wrapper = new LambdaUpdateWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getId, id)
                .in(OfflineImportTask::getStatus, "PENDING", "SUCCESS", "FAILED")
                .set(OfflineImportTask::getStatus, "RUNNING")
                .set(OfflineImportTask::getUpdateTime, updateTime)
                .set(OfflineImportTask::getStartTime, updateTime)
                .set(OfflineImportTask::getEndTime, null)
                .set(OfflineImportTask::getErrorMessage, null);
        return mybatisMapper.update(null, wrapper) > 0;
    }

    @Override
    public int failAllRunning(String errorMessage) {
        LambdaUpdateWrapper<OfflineImportTask> wrapper = new LambdaUpdateWrapper<OfflineImportTask>()
                .eq(OfflineImportTask::getStatus, "RUNNING")
                .set(OfflineImportTask::getStatus, "FAILED")
                .set(OfflineImportTask::getErrorMessage, errorMessage)
                .set(OfflineImportTask::getEndTime, new Date());
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
}
