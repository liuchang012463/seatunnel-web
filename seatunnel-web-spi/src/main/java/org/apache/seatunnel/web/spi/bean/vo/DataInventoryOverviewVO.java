package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

/** One inventory snapshot used by the overview page to avoid duplicate scans. */
@Data
public class DataInventoryOverviewVO {
    private DataInventorySummaryVO summary;
    private List<DataInventoryDistributionVO> sourceTypes;
    private List<DataInventoryDistributionVO> units;
    private List<DataInventoryDistributionVO> businessSystems;
    private DataInventoryProfileCoverageVO coverage;
    private long generatedAt;
}
