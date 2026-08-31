package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;

public interface LakeExternalCatalogBindingDao extends IDao<LakeExternalCatalogBinding> {

    LakeExternalCatalogBinding queryActiveById(Long id);

    LakeExternalCatalogBinding queryByIdIncludingDeleted(Long id);

    LakeExternalCatalogBinding queryBySourceDataSourceId(Long sourceDataSourceId);

    LakeExternalCatalogBinding queryByLakeDataSourceIdAndCatalogName(
            Long lakeDataSourceId, String catalogName);

    boolean updateIfTokenAndVersion(LakeExternalCatalogBinding entity, String operationToken, Integer lockVersion);

    boolean updateIfTokenAndVersionIncludingDeleted(
            LakeExternalCatalogBinding entity, String operationToken, Integer lockVersion);
}
