package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.spi.bean.vo.DataInventoryDistributionVO;
import org.apache.seatunnel.web.spi.bean.vo.DataInventoryProfileCoverageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataInventorySummaryVO;

import java.util.List;

/**
 * Serializable projection of one data-inventory aggregate build.  Both cache
 * implementations (process-local and Redis) store this shape so the endpoint
 * contract never changes.
 */
public record InventorySnapshotPayload(
        DataInventorySummaryVO summary,
        List<DataInventoryDistributionVO> sourceTypes,
        List<DataInventoryDistributionVO> units,
        List<DataInventoryDistributionVO> businessSystems,
        DataInventoryProfileCoverageVO coverage) {

    public InventorySnapshotPayload {
        sourceTypes = sourceTypes == null ? List.of() : List.copyOf(sourceTypes);
        units = units == null ? List.of() : List.copyOf(units);
        businessSystems = businessSystems == null ? List.of() : List.copyOf(businessSystems);
    }
}
