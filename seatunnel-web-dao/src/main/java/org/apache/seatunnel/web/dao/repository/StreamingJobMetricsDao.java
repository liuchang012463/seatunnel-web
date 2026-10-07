package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.StreamingJobMetrics;

import java.util.List;
import java.util.Map;

public interface StreamingJobMetricsDao extends IDao<StreamingJobMetrics> {

    Map<String, Object> selectOverviewSummary(long startTimeMs, long endTimeMs);

    List<Map<String, Object>> selectOverviewTrend(long startTimeMs, long endTimeMs, String granularity);

    StreamingJobMetrics selectLatestByInstanceId(Long instanceId);

    List<StreamingJobMetrics> selectByInstanceIdAndTimeRange(Long instanceId,
                                                             Long startTimeMs,
                                                             Long endTimeMs);

    List<StreamingJobMetrics> selectRecentByInstanceId(Long instanceId, Integer limit);

    void deleteByInstanceId( Long instanceId);

    void deleteByDefinitionId(Long definitionId);

    void deleteBefore( Long collectTimeMs);
}
