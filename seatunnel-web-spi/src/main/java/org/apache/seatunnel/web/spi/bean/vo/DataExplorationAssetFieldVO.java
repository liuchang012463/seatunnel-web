package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** One schema entry of an exploration asset. */
@Data
public class DataExplorationAssetFieldVO {
    private String name;
    private String dataType;
    private String description;
    private List<String> tags = new ArrayList<>();
    private List<DataExplorationAssetFieldVO> children = new ArrayList<>();
}
