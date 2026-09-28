package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.seatunnel.web.dao.entity.StreamingJobTableMetricsCurrent;

import java.util.List;

@Mapper
public interface StreamingJobTableMetricsCurrentMapper extends BaseMapper<StreamingJobTableMetricsCurrent> {

    void upsertBatch(List<StreamingJobTableMetricsCurrent> metricsList);
}
