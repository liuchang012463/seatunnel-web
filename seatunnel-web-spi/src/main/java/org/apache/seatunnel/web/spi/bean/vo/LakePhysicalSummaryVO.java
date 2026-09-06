package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Local control-plane counters used by the physical lake workbench. */
@Data
public class LakePhysicalSummaryVO {

    private long boundDataSourceCount;
    private long odsTableCount;
    private long pendingExceptionCount;
}
