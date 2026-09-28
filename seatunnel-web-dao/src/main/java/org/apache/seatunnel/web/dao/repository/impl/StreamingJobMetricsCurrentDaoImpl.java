package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.StreamingJobMetricsCurrent;
import org.apache.seatunnel.web.dao.mapper.StreamingJobMetricsCurrentMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.apache.seatunnel.web.dao.repository.StreamingJobMetricsCurrentDao;
import org.springframework.stereotype.Repository;

@Repository
public class StreamingJobMetricsCurrentDaoImpl
        extends BaseDao<StreamingJobMetricsCurrent, StreamingJobMetricsCurrentMapper>
        implements StreamingJobMetricsCurrentDao {

    @Resource
    private StreamingJobMetricsCurrentMapper streamingJobMetricsCurrentMapper;

    public StreamingJobMetricsCurrentDaoImpl(
            @NonNull StreamingJobMetricsCurrentMapper streamingJobMetricsCurrentMapper) {
        super(streamingJobMetricsCurrentMapper);
    }

    @Override
    public void upsert(StreamingJobMetricsCurrent metrics) {
        if (metrics == null || metrics.getJobInstanceId() == null) {
            return;
        }

        streamingJobMetricsCurrentMapper.upsert(metrics);
    }

    @Override
    public StreamingJobMetricsCurrent selectByInstanceId(Long instanceId) {
        if (instanceId == null || instanceId <= 0) {
            return null;
        }

        return streamingJobMetricsCurrentMapper.selectOne(
                new LambdaQueryWrapper<StreamingJobMetricsCurrent>()
                        .eq(MyBatisColumn.getter(StreamingJobMetricsCurrent::getJobInstanceId), instanceId)
                        .last("LIMIT 1")
        );
    }

    @Override
    public void deleteByInstanceId(Long instanceId) {
        if (instanceId == null || instanceId <= 0) {
            return;
        }

        streamingJobMetricsCurrentMapper.delete(
                new LambdaQueryWrapper<StreamingJobMetricsCurrent>()
                        .eq(MyBatisColumn.getter(StreamingJobMetricsCurrent::getJobInstanceId), instanceId)
        );
    }

    @Override
    public void deleteByDefinitionId(Long definitionId) {
        if (definitionId == null || definitionId <= 0) {
            return;
        }

        streamingJobMetricsCurrentMapper.delete(
                new LambdaQueryWrapper<StreamingJobMetricsCurrent>()
                        .eq(MyBatisColumn.getter(StreamingJobMetricsCurrent::getJobDefinitionId), definitionId)
        );
    }
}
