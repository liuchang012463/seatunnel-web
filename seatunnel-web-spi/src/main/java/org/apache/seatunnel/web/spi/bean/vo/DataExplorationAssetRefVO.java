package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** One ancestor in the exploration breadcrumb. */
@Data
public class DataExplorationAssetRefVO {
    private String id;
    private String name;
    private String assetType;
    private String fullyQualifiedName;
    private String description;
}
