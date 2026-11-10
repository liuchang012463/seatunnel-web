package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Aggregated data-inventory counters. Counts are intentionally long-valued. */
@Data
public class DataInventorySummaryVO {

    private long unitCount;
    private long businessSystemCount;
    private long dataSourceCount;
    private long databaseCount;
    private long schemaCount;
    private long tableCount;
    private long columnCount;
    private long profiledDatabaseCount;
    /** Discovered table structures; this API field name is retained for compatibility. */
    private long profiledTableCount;
    private long knownRowCount;
    private long knownSizeInByte;
}
