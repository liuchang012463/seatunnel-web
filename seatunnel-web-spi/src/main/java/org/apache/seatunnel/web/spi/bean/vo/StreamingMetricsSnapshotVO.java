package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Data
public class StreamingMetricsSnapshotVO {

    private Long collectTimeMs;

    private Long jobInstanceId;

    private Long jobDefinitionId;

    private String engineJobId;

    private Long clientId;

    private String jobStatus;

    private Long readRowCount;

    private Long writeRowCount;

    private BigDecimal readQps;

    private BigDecimal writeQps;

    private Long readBytes;

    private Long writeBytes;

    private BigDecimal readBps;

    private BigDecimal writeBps;

    /** Distinguishes a missing current snapshot from measured zero counters. */
    private Boolean metricsAvailable;

    private Long intermediateQueueSize;

    private List<StreamingTableMetricsVO> tableMetrics = Collections.emptyList();
}
