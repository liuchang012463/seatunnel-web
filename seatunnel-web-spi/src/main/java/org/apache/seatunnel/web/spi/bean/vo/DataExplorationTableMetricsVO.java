package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Known table-level profile metrics. Unsupported/missing metrics remain null. */
@Data
public class DataExplorationTableMetricsVO {
    private Long rowCount;
    private Long columnCount;
    private Long sizeInByte;
}
