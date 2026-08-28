package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Foreign-key relationship between two ER table nodes. */
@Data
public class DataExplorationErEdgeVO {
    private String id;
    private String type = "FOREIGN_KEY";
    private DataExplorationErEndpointVO source;
    private DataExplorationErEndpointVO target;
}
