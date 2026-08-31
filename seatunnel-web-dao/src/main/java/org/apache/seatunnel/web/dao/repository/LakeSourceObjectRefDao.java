package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;

public interface LakeSourceObjectRefDao extends IDao<LakeSourceObjectRef> {

    LakeSourceObjectRef queryByOmEntityId(String omEntityId);

    LakeSourceObjectRef queryBySourceDataSourceIdAndOmEntityId(Long sourceDataSourceId, String omEntityId);

    boolean updateIfTokenAndVersion(LakeSourceObjectRef entity, String operationToken, Integer lockVersion);
}
