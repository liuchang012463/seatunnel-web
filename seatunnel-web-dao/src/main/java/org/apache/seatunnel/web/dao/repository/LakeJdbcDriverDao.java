package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeJdbcDriver;

import java.util.List;

public interface LakeJdbcDriverDao extends IDao<LakeJdbcDriver> {

    LakeJdbcDriver queryByAdapter(String adapter);

    List<LakeJdbcDriver> queryEnabled();

    int updateDriver(LakeJdbcDriver driver);
}
