package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

/** One listing of the non-relational OpenMetadata assets of a data source. */
@Data
public class DataSourceResourcePageVO {
    private List<DataSourceResourceVO> resources;
    private List<String> entityTypes;
    private boolean truncated;
}
