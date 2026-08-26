package org.apache.seatunnel.web.api.metadata.client;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Latest table profile plus the column profiles embedded by the 1.12.10 API. */
@Data
public class OpenMetadataTableProfile {

    private String tableId;
    private String tableName;
    private String tableFullyQualifiedName;
    private Long timestamp;
    private Long profileSample;
    private String profileSampleType;
    private Long rowCount;
    private Long columnCount;
    private Long sizeInByte;
    private List<OpenMetadataColumnProfile> columns = new ArrayList<>();
}
