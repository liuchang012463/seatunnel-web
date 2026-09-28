package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.IncrementalBatchRecord;


public interface IncrementalBatchRecordDao extends IDao<IncrementalBatchRecord> {

    IncrementalBatchRecord queryRunningByDefinitionId(Long jobDefinitionId);

    IncrementalBatchRecord queryLatestFailedByDefinitionId(Long jobDefinitionId);

    IncrementalBatchRecord queryByInstanceId(Long jobInstanceId);

    boolean deleteByDefinitionId(Long jobDefinitionId);
}
