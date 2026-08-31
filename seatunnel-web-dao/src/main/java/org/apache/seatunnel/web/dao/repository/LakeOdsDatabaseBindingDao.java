package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

import java.util.List;

public interface LakeOdsDatabaseBindingDao extends IDao<LakeOdsDatabaseBinding> {

    LakeOdsDatabaseBinding queryActiveById(Long id);

    LakeOdsDatabaseBinding queryByIdIncludingDeleted(Long id);

    LakeOdsDatabaseBinding queryBySourceDataSourceId(Long sourceDataSourceId);

    LakeOdsDatabaseBinding queryBySourceDataSourceIdIncludingDeleted(Long sourceDataSourceId);

    LakeOdsDatabaseBinding queryByLakeDataSourceIdAndDatabaseName(
            Long lakeDataSourceId, String databaseName);

    LakeOdsDatabaseBinding queryByLakeDataSourceIdAndDatabaseNameIncludingDeleted(
            Long lakeDataSourceId, String databaseName);

    boolean existsActiveBySourceDataSourceId(Long sourceDataSourceId);

    boolean existsActiveByLakeDataSourceId(Long lakeDataSourceId);

    /** Returns source IDs for active bindings in the requested resource state. */
    List<Long> querySourceDataSourceIdsByResourceStatus(LakeResourceStatus resourceStatus);

    boolean updateIfTokenAndVersion(LakeOdsDatabaseBinding entity, String operationToken, Integer lockVersion);

    boolean updateIfTokenAndVersionIncludingDeleted(
            LakeOdsDatabaseBinding entity, String operationToken, Integer lockVersion);
}
