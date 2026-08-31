package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Table row used by the scan-result list; metadata remains owned by OpenMetadata. */
@Data
public class DataExplorationTableVO {
    private String id;
    private String name;
    private String displayName;
    private String fullyQualifiedName;
    private String tableType;
    private String description;
    private Integer columnCount;
    private boolean profileAvailable;
    private Long profileTime;
}
