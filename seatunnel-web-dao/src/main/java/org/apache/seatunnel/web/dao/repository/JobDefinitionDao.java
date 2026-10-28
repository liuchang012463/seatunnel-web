package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.spi.bean.dto.BatchJobDefinitionQueryDTO;
import org.apache.seatunnel.web.spi.bean.vo.BatchJobDefinitionVO;

import java.util.Date;
import java.util.List;

public interface JobDefinitionDao extends IDao<JobDefinitionEntity> {

    boolean saveOrUpdate(JobDefinitionEntity po);

    List<BatchJobDefinitionVO> selectPageWithLatestInstance(
            BatchJobDefinitionQueryDTO dto,
            int offset,
            int pageSize
    );

    Long count(BatchJobDefinitionQueryDTO dto);

    boolean updateReleaseState(
            Long id,
            ReleaseState releaseState,
            Integer updateUserId,
            Date updateTime
    );

    List<JobDefinitionEntity> listByIds(List<Long> ids);

    boolean existsByDatasourceId(Long datasourceId);

    boolean existsBySourceDatasourceId(Long datasourceId);

    boolean existsBySinkDatasourceId(Long datasourceId);

    List<Long> selectReferencedDatasourceIds(List<Long> datasourceIds);

    boolean existsByClientId(Long clientId);

    /**
     * Locks the definition row for the current transaction. Concurrent submitters on different
     * Web instances serialize on this row before checking for a running instance.
     */
    void lockById(Long id);
}
