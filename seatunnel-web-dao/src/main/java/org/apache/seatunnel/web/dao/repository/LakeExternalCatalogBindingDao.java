package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;

public interface LakeExternalCatalogBindingDao extends IDao<LakeExternalCatalogBinding> {

    LakeExternalCatalogBinding queryActiveById(Long id);

    LakeExternalCatalogBinding queryByIdIncludingDeleted(Long id);

    LakeExternalCatalogBinding queryBySourceDataSourceId(Long sourceDataSourceId);

    /** Queries the unique source row even when it is a retained tombstone. */
    LakeExternalCatalogBinding queryBySourceDataSourceIdIncludingDeleted(Long sourceDataSourceId);

    LakeExternalCatalogBinding queryByLakeDataSourceIdAndCatalogName(
            Long lakeDataSourceId, String catalogName);

    /** Queries the target reservation including a retained tombstone. */
    LakeExternalCatalogBinding queryByLakeDataSourceIdAndCatalogNameIncludingDeleted(
            Long lakeDataSourceId, String catalogName);

    /** Active local read model; this method never contacts Doris. */
    IPage<LakeExternalCatalogBinding> queryActivePage(
            IPage<LakeExternalCatalogBinding> page,
            Long lakeDataSourceId,
            Long sourceDataSourceId,
            String catalogName,
            String adapter,
            String resourceStatus,
            String validationStatus);

    boolean existsActiveBySourceDataSourceId(Long sourceDataSourceId);

    boolean existsActiveByTarget(Long lakeDataSourceId, String catalogName);

    boolean updateIfTokenAndVersion(LakeExternalCatalogBinding entity, String operationToken, Integer lockVersion);

    boolean updateIfTokenAndVersionIncludingDeleted(
            LakeExternalCatalogBinding entity, String operationToken, Integer lockVersion);
}
