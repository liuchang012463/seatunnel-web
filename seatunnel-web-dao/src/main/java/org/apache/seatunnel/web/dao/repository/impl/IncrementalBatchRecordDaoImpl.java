package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.IncrementalBatchRecord;
import org.apache.seatunnel.web.dao.mapper.IncrementalBatchRecordMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.IncrementalBatchRecordDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.springframework.stereotype.Repository;

@Repository
public class IncrementalBatchRecordDaoImpl
        extends BaseDao<IncrementalBatchRecord, IncrementalBatchRecordMapper>
        implements IncrementalBatchRecordDao {

    private final IncrementalBatchRecordMapper mapper;

    public IncrementalBatchRecordDaoImpl(@NonNull IncrementalBatchRecordMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public IncrementalBatchRecord queryRunningByDefinitionId(Long jobDefinitionId) {
        return mapper.selectOne(new LambdaQueryWrapper<IncrementalBatchRecord>()
                .eq(MyBatisColumn.getter(IncrementalBatchRecord::getJobDefinitionId), jobDefinitionId)
                .eq(MyBatisColumn.getter(IncrementalBatchRecord::getBatchStatus), "RUNNING")
                .orderByDesc(MyBatisColumn.getter(IncrementalBatchRecord::getCreateTime))
                .last("LIMIT 1 FOR UPDATE"));
    }

    @Override
    public IncrementalBatchRecord queryLatestFailedByDefinitionId(Long jobDefinitionId) {
        return mapper.selectOne(new LambdaQueryWrapper<IncrementalBatchRecord>()
                .eq(MyBatisColumn.getter(IncrementalBatchRecord::getJobDefinitionId), jobDefinitionId)
                .eq(MyBatisColumn.getter(IncrementalBatchRecord::getBatchStatus), "FAILED")
                .orderByDesc(MyBatisColumn.getter(IncrementalBatchRecord::getCreateTime))
                .last("LIMIT 1"));
    }

    @Override
    public IncrementalBatchRecord queryByInstanceId(Long jobInstanceId) {
        return mapper.selectOne(new LambdaQueryWrapper<IncrementalBatchRecord>()
                .eq(MyBatisColumn.getter(IncrementalBatchRecord::getJobInstanceId), jobInstanceId)
                .last("LIMIT 1"));
    }

    @Override
    public boolean deleteByDefinitionId(Long jobDefinitionId) {
        return mapper.delete(new LambdaQueryWrapper<IncrementalBatchRecord>()
                .eq(MyBatisColumn.getter(IncrementalBatchRecord::getJobDefinitionId), jobDefinitionId)) > 0;
    }
}
