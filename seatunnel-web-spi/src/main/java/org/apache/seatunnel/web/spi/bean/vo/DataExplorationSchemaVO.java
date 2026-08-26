package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** OpenMetadata DatabaseSchema projection returned by the data-exploration API. */
@Data
public class DataExplorationSchemaVO {
    private String id;
    private String name;
    private String fullyQualifiedName;
    private String databaseFullyQualifiedName;
}
