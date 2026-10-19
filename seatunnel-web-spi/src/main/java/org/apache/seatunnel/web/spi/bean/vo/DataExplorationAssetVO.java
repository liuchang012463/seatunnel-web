package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Summary of one non-relational OpenMetadata asset. */
@Data
public class DataExplorationAssetVO {
    private String id;
    private String name;
    private String displayName;
    private String fullyQualifiedName;
    /** API spelling, e.g. TOPIC or SEARCH_INDEX. */
    private String assetType;
    /** Product label, e.g. 主题 or 索引. */
    private String assetLabel;
    private String description;
    private boolean hasChildren;
    private Integer childCount;
    private Integer fieldCount;
    private List<String> tags = new ArrayList<>();
    private Long sizeInByte;
    private Long updatedAt;
}
