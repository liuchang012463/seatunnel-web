package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Table discovery coverage and the explicitly known profile-derived row volume. */
@Data
public class DataInventoryProfileCoverageVO {

    private long databaseCount;
    private long profiledDatabaseCount;
    private long tableCount;
    /** Discovered table structures; this API field name is retained for compatibility. */
    private long profiledTableCount;
    private long knownRowCount;
    private long knownSizeInByte;
    private double tableCoveragePercent;
}
