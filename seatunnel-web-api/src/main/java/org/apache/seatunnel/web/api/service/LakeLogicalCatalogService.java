package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeLogicalCapabilityVO;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;

/** Read-only logical catalog resource facade for the initial API slice. */
public interface LakeLogicalCatalogService {

    LakeLogicalCapabilityVO capability(Long sourceDataSourceId);

    LakeLogicalCapabilityVO capability(
            Long sourceDataSourceId, LakeJdbcAdapterType adapter, LakeCatalogScope scope);

    PaginationResult<LakeExternalCatalogVO> page(LakeExternalCatalogPageDTO request);

    LakeExternalCatalogVO detail(Long bindingId);

    LakeExternalCatalogVO create(LakeExternalCatalogCreateDTO request);

    LakeExternalCatalogVO update(Long bindingId, LakeExternalCatalogUpdateDTO request);

    LakeExternalCatalogVO validate(Long bindingId);

    LakeExternalCatalogVO refresh(Long bindingId);

    LakeExternalCatalogVO reconcile(Long bindingId);

    LakeExternalCatalogVO delete(Long bindingId);
}
