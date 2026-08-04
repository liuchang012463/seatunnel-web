package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.IncrementalBatchControl;

public interface IncrementalBatchControlDao extends IDao<IncrementalBatchControl> {

    IncrementalBatchControl queryByDefinitionIdForUpdate(Long jobDefinitionId);

    boolean updateWatermark(IncrementalBatchControl control,
                            java.util.Date committedWatermark,
                            String lastSuccessBatchId);

    boolean updateStatus(Long id, String taskStatus, java.util.Date updateTime);

    boolean deleteByDefinitionId(Long jobDefinitionId);
}
