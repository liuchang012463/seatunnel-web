package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.query.LakeQueryColumnOptionVO;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryPreviewVO;
import org.apache.seatunnel.web.api.lake.query.LakeReadOnlyQueryResultVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeJoinQueryDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeQueryTableIdentityDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeSingleTableQueryDTO;

import java.util.List;

/** Structured, bounded read-only queries over the lake's internal and external tables. */
public interface LakeLogicalCatalogQueryService {

    LakeReadOnlyQueryResultVO sample(LakeQueryTableIdentityDTO table);

    List<LakeQueryColumnOptionVO> queryColumns(LakeQueryTableIdentityDTO table);

    LakeReadOnlyQueryResultVO single(Long catalogBindingId, LakeSingleTableQueryDTO request);

    LakeReadOnlyQueryResultVO join(LakeJoinQueryDTO request);

    /** Requests cancellation of an in-flight query identified by its opaque client id. */
    boolean cancel(String queryId);

    /** Builds the same bounded SQL as execution, without opening a query connection. */
    LakeReadOnlyQueryPreviewVO previewSingle(Long catalogBindingId, LakeSingleTableQueryDTO request);

    /** Builds a safe two-table JOIN preview without executing it. */
    LakeReadOnlyQueryPreviewVO previewJoin(LakeJoinQueryDTO request);

    List<String> databases(Long catalogBindingId);

    List<String> tables(Long catalogBindingId, String database);

    List<LakeQueryColumnOptionVO> columns(
            Long catalogBindingId, String database, String table);
}
