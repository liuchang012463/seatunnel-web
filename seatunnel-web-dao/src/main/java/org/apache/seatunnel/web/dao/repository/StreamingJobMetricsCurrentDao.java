package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.StreamingJobMetricsCurrent;

public interface StreamingJobMetricsCurrentDao extends IDao<StreamingJobMetricsCurrent> {

    void upsert(StreamingJobMetricsCurrent metrics);

    StreamingJobMetricsCurrent selectByInstanceId(Long instanceId);

    void deleteByInstanceId(Long instanceId);

    void deleteByDefinitionId(Long definitionId);
}
