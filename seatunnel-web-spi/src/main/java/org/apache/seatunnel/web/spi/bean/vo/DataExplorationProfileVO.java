package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Latest successful/current profile projection; the source of truth stays in OpenMetadata. */
@Data
public class DataExplorationProfileVO {
    private Long profileTime;
    private DataExplorationTableMetricsVO table;
    private List<DataExplorationColumnProfileVO> columns = new ArrayList<>();
}
