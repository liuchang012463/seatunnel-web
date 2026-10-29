package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.spi.bean.dto.StreamingJobDefinitionQueryDTO;
import org.apache.seatunnel.web.spi.bean.vo.StreamingJobDefinitionVO;

import java.util.Date;
import java.util.List;

public interface StreamingJobDefinitionDao extends IDao<StreamingJobDefinitionEntity> {

    StreamingJobDefinitionEntity queryById(Long id);

    /** Locks a definition row to serialize streaming instance creation. */
    void lockById(Long id);

    void saveOrUpdate(StreamingJobDefinitionEntity entity);

    boolean deleteById(Long id);

    boolean updateReleaseState(
            Long id,
            ReleaseState releaseState,
            Integer updateUserId,
            Date updateTime
    );

    List<StreamingJobDefinitionVO> selectPage(
            StreamingJobDefinitionQueryDTO dto,
            int offset,
            int pageSize
    );

    Long count(StreamingJobDefinitionQueryDTO dto);

    boolean existsByDatasourceId(Long datasourceId);

    boolean existsBySourceDatasourceId(Long datasourceId);

    boolean existsBySinkDatasourceId(Long datasourceId);

    List<Long> selectReferencedDatasourceIds(List<Long> datasourceIds);

    boolean existsByClientId(Long clientId);
}
