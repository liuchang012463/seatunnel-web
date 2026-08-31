package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;

public interface LakeTableLifecycleBindingDao extends IDao<LakeTableLifecycleBinding> {

    LakeTableLifecycleBinding queryByTableMappingId(Long tableMappingId);

    boolean updateIfTokenAndVersion(LakeTableLifecycleBinding entity, String operationToken, Integer lockVersion);
}
