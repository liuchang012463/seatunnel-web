package org.apache.seatunnel.web.dao.repository;

import org.apache.ibatis.annotations.Param;
import org.apache.seatunnel.web.dao.entity.StreamingJobMetrics;

import java.util.List;

public interface StreamingJobMetricsDao extends IDao<StreamingJobMetrics> {

    StreamingJobMetrics selectLatestByInstanceId(Long instanceId);

    List<StreamingJobMetrics> selectByInstanceIdAndTimeRange(Long instanceId,
                                                             Long startTimeMs,
                                                             Long endTimeMs);

    List<StreamingJobMetrics> selectRecentByInstanceId(Long instanceId, Integer limit);

    void deleteByInstanceId( Long instanceId);

    void deleteByDefinitionId(Long definitionId);

    void deleteBefore( Long collectTimeMs);
}
