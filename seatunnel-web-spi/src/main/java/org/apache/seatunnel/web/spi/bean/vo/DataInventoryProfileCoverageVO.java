package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Profile coverage and the explicitly known (profiled) row volume. */
@Data
public class DataInventoryProfileCoverageVO {

    private long databaseCount;
    private long profiledDatabaseCount;
    private long tableCount;
    private long profiledTableCount;
    private long knownRowCount;
    private double tableCoveragePercent;
}
