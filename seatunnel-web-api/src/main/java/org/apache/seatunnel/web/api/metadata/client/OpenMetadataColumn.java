package org.apache.seatunnel.web.api.metadata.client;

import lombok.Data;

/** Minimal Column projection returned by OpenMetadata Table APIs. */
@Data
public class OpenMetadataColumn {

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
    private OpenMetadataColumnProfile profile;
}
