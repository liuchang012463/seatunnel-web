package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;

public interface LakeOdsDatabaseBindingDao extends IDao<LakeOdsDatabaseBinding> {

    LakeOdsDatabaseBinding queryBySourceDataSourceId(Long sourceDataSourceId);

    LakeOdsDatabaseBinding queryByLakeDataSourceIdAndDatabaseName(
            Long lakeDataSourceId, String databaseName);

    boolean updateIfTokenAndVersion(LakeOdsDatabaseBinding entity, String operationToken, Integer lockVersion);
}
