package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryResultVO;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryPreviewVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeJoinQueryDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeSingleTableQueryDTO;

import java.util.List;

/** Structured, bounded read-only query facade for logical catalogs. */
public interface LakeLogicalCatalogQueryService {

    LakeReadOnlyQueryResultVO single(Long catalogBindingId, LakeSingleTableQueryDTO request);

    LakeReadOnlyQueryResultVO join(LakeJoinQueryDTO request);

    /** Builds the same bounded SQL as execution, without opening a query connection. */
    LakeReadOnlyQueryPreviewVO previewSingle(Long catalogBindingId, LakeSingleTableQueryDTO request);

    /** Builds a safe two-catalog JOIN preview without executing it. */
    LakeReadOnlyQueryPreviewVO previewJoin(LakeJoinQueryDTO request);

    List<String> databases(Long catalogBindingId);

    List<String> tables(Long catalogBindingId, String database);

    List<org.apache.seatunnel.web.api.lake.query.LakeQueryColumnOptionVO> columns(
            Long catalogBindingId, String database, String table);
}
