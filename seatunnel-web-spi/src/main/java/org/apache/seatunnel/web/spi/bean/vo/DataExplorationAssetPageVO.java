package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** One level of the non-relational exploration hierarchy. */
@Data
public class DataExplorationAssetPageVO {
    private List<DataExplorationAssetRefVO> breadcrumbs = new ArrayList<>();
    private DataExplorationAssetRefVO parent;
    private List<DataExplorationAssetVO> records = new ArrayList<>();
    private long total;
    private int pageNo;
    private int pageSize;
    /** True when the backend stopped scanning before the level was exhausted. */
    private boolean truncated;
}
