package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionContentEntity;

import java.util.List;

public interface StreamingJobDefinitionContentDao {

    List<StreamingJobDefinitionContentEntity> queryAll();

    void save(StreamingJobDefinitionContentEntity entity);

    StreamingJobDefinitionContentEntity queryLatestByJobDefinitionId(Long jobDefinitionId);

    boolean existsByFileResourceId(String fileResourceId);

    void deleteByJobDefinitionId(Long jobDefinitionId);
}
