package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

/** One schema entry of a non-relational OpenMetadata asset. */
@Data
public class DataSourceResourceFieldVO {
    private String name;
    private String dataType;
    private String description;
    private List<String> tags;
}
