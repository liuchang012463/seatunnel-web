package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.seatunnel.web.dao.entity.StreamingJobMetricsCurrent;

@Mapper
public interface StreamingJobMetricsCurrentMapper extends BaseMapper<StreamingJobMetricsCurrent> {

    void upsert(StreamingJobMetricsCurrent metrics);
}
