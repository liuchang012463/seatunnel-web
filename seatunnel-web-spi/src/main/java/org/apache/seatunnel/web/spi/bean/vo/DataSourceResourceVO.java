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
    private Integer fieldCount;
    private List<String> tags;
}
