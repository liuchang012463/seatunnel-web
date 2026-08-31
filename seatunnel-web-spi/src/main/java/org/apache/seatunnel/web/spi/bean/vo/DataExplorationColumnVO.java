package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Column structure projection for a scanned table. */
@Data
public class DataExplorationColumnVO {
    private String name;
    private String fullyQualifiedName;
    private String dataType;
    private String dataTypeDisplay;
    private Long dataLength;
    private Long precision;
    private Long scale;
    private String description;
    private String constraint;
    private Integer ordinalPosition;
}
