package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

@Data
public class OverviewSummaryVO {
    private double totalRecords;
    private double totalBytes;
    private long totalTasks;
    private long successTasks;
    private long failedTasks;
    private long runningTasks;
    private long stoppedTasks;
    private long avgRecordDelay;
    private String totalRecordsUnit;
    private String totalBytesUnit;

}
