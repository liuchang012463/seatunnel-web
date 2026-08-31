package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;

public interface LakeOdsDatabaseBindingDao extends IDao<LakeOdsDatabaseBinding> {

    LakeOdsDatabaseBinding queryActiveById(Long id);

    LakeOdsDatabaseBinding queryByIdIncludingDeleted(Long id);

    LakeOdsDatabaseBinding queryBySourceDataSourceId(Long sourceDataSourceId);

    LakeOdsDatabaseBinding queryByLakeDataSourceIdAndDatabaseName(
            Long lakeDataSourceId, String databaseName);

    boolean updateIfTokenAndVersion(LakeOdsDatabaseBinding entity, String operationToken, Integer lockVersion);

    boolean updateIfTokenAndVersionIncludingDeleted(
            LakeOdsDatabaseBinding entity, String operationToken, Integer lockVersion);
}
