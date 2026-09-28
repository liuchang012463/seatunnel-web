package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.StreamingJobTableMetricsCurrent;

import java.util.List;

public interface StreamingJobTableMetricsCurrentDao extends IDao<StreamingJobTableMetricsCurrent> {

    void upsertBatch(List<StreamingJobTableMetricsCurrent> metricsList);

    List<StreamingJobTableMetricsCurrent> selectByInstanceId(Long instanceId);

    void deleteByInstanceId(Long instanceId);

    void deleteByDefinitionId(Long definitionId);
}
