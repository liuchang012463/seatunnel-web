package org.apache.seatunnel.web.spi.bean.dto;

import lombok.Data;

/** Structured auto-range partition input; it is never accepted as SQL. */
@Data
public class LakeManagedTablePartitionDTO {

    private Boolean enabled;

    private String column;

    private String granularity;
}
