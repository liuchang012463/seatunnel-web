package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Full detail of one non-relational OpenMetadata asset. */
@Data
public class DataExplorationAssetDetailVO {
    private DataExplorationAssetVO asset;
    private List<DataExplorationAssetRefVO> breadcrumbs = new ArrayList<>();
    private DataExplorationAssetSchemaVO schema = new DataExplorationAssetSchemaVO();
    private DataExplorationAssetSampleVO sample = new DataExplorationAssetSampleVO();
    private List<DataExplorationAssetPropertyVO> properties = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private String description;
    private Long updatedAt;
}
