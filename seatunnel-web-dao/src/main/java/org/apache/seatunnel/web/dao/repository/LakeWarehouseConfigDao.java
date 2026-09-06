package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeWarehouseConfig;

public interface LakeWarehouseConfigDao extends IDao<LakeWarehouseConfig> {

    LakeWarehouseConfig querySingleton();

    int updateSingleton(LakeWarehouseConfig config);
}
