package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** One inventory snapshot used by the overview page to avoid duplicate scans. */
@Data
public class DataInventoryOverviewVO {
    private DataInventorySummaryVO summary;
    private DataInventoryProfileCoverageVO coverage;
    private long generatedAt;
}
