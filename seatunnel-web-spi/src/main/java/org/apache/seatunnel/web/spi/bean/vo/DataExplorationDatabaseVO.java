package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** OpenMetadata Database projection returned by the data-exploration API. */
@Data
public class DataExplorationDatabaseVO {
    private String id;
    private String name;
    private String fullyQualifiedName;
}
