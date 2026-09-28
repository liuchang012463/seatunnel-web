package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.IncrementalBatchControl;
import org.apache.seatunnel.web.dao.mapper.IncrementalBatchControlMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.IncrementalBatchControlDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
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
                .eq(MyBatisColumn.getter(IncrementalBatchControl::getJobDefinitionId), jobDefinitionId)
                .last("LIMIT 1 FOR UPDATE"));
    }

    @Override
    public boolean updateWatermark(IncrementalBatchControl control,
                                   Date committedWatermark,
                                   String lastSuccessBatchId) {
        LambdaUpdateWrapper<IncrementalBatchControl> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MyBatisColumn.getter(IncrementalBatchControl::getId), control.getId())
                .eq(MyBatisColumn.getter(IncrementalBatchControl::getVersionNo), control.getVersionNo())
                .set(MyBatisColumn.getter(IncrementalBatchControl::getCommittedWatermark), committedWatermark)
                .set(MyBatisColumn.getter(IncrementalBatchControl::getLastSuccessBatchId), lastSuccessBatchId)
                .set(MyBatisColumn.getter(IncrementalBatchControl::getTaskStatus), "READY")
                .set(MyBatisColumn.getter(IncrementalBatchControl::getVersionNo), control.getVersionNo() + 1)
                .set(MyBatisColumn.getter(IncrementalBatchControl::getUpdateTime), new Date());
        return mapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean updateStatus(Long id, String taskStatus, Date updateTime) {
        LambdaUpdateWrapper<IncrementalBatchControl> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MyBatisColumn.getter(IncrementalBatchControl::getId), id)
                .set(MyBatisColumn.getter(IncrementalBatchControl::getTaskStatus), taskStatus)
                .set(MyBatisColumn.getter(IncrementalBatchControl::getUpdateTime), updateTime);
        return mapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean deleteByDefinitionId(Long jobDefinitionId) {
        return mapper.delete(new LambdaQueryWrapper<IncrementalBatchControl>()
                .eq(MyBatisColumn.getter(IncrementalBatchControl::getJobDefinitionId), jobDefinitionId)) > 0;
    }
}
