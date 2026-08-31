package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;

public interface LakeExternalCatalogBindingDao extends IDao<LakeExternalCatalogBinding> {

    LakeExternalCatalogBinding queryBySourceDataSourceId(Long sourceDataSourceId);

    LakeExternalCatalogBinding queryByLakeDataSourceIdAndCatalogName(
            Long lakeDataSourceId, String catalogName);

    boolean updateIfTokenAndVersion(LakeExternalCatalogBinding entity, String operationToken, Integer lockVersion);
}
