package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** One named bucket in a data-inventory distribution. */
@Data
public class DataInventoryDistributionVO {

    private String key;
    private String name;
    private long count;
}
