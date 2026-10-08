package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

/** One non-relational OpenMetadata asset exposed for a data source. */
@Data
public class DataSourceResourceVO {
    private String id;
    private String name;
    private String fullyQualifiedName;
    private String entityType;
    private String entityLabel;
    private String description;
    /** Size of the asset's own schema, when it has one. */
    private Integer fieldCount;
    /** Number of assets it contains, for example an API collection's endpoints. */
    private Integer childCount;
    private List<String> tags;
}
