package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.IncrementalBatchControl;
import org.apache.seatunnel.web.dao.mapper.IncrementalBatchControlMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.IncrementalBatchControlDao;
import org.springframework.stereotype.Repository;

import java.util.Date;

@Repository
public class IncrementalBatchControlDaoImpl
        extends BaseDao<IncrementalBatchControl, IncrementalBatchControlMapper>
        implements IncrementalBatchControlDao {

    private final IncrementalBatchControlMapper mapper;

    public IncrementalBatchControlDaoImpl(@NonNull IncrementalBatchControlMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public IncrementalBatchControl queryByDefinitionIdForUpdate(Long jobDefinitionId) {
        return mapper.selectOne(new LambdaQueryWrapper<IncrementalBatchControl>()
                .eq(IncrementalBatchControl::getJobDefinitionId, jobDefinitionId)
                .last("LIMIT 1 FOR UPDATE"));
    }

    @Override
    public boolean updateWatermark(IncrementalBatchControl control,
                                   Date committedWatermark,
                                   String lastSuccessBatchId) {
        LambdaUpdateWrapper<IncrementalBatchControl> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(IncrementalBatchControl::getId, control.getId())
                .eq(IncrementalBatchControl::getVersionNo, control.getVersionNo())
                .set(IncrementalBatchControl::getCommittedWatermark, committedWatermark)
                .set(IncrementalBatchControl::getLastSuccessBatchId, lastSuccessBatchId)
                .set(IncrementalBatchControl::getTaskStatus, "READY")
                .set(IncrementalBatchControl::getVersionNo, control.getVersionNo() + 1)
                .set(IncrementalBatchControl::getUpdateTime, new Date());
        return mapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean updateStatus(Long id, String taskStatus, Date updateTime) {
        LambdaUpdateWrapper<IncrementalBatchControl> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(IncrementalBatchControl::getId, id)
                .set(IncrementalBatchControl::getTaskStatus, taskStatus)
                .set(IncrementalBatchControl::getUpdateTime, updateTime);
        return mapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean deleteByDefinitionId(Long jobDefinitionId) {
        return mapper.delete(new LambdaQueryWrapper<IncrementalBatchControl>()
                .eq(IncrementalBatchControl::getJobDefinitionId, jobDefinitionId)) > 0;
    }
}
