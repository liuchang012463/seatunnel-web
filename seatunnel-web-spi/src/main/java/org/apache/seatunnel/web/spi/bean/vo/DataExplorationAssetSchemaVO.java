package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema projection of an exploration asset. API endpoints keep request and response
 * schemas separate; every other family uses {@code fields}.
 */
@Data
public class DataExplorationAssetSchemaVO {
    private String kind;
    private String status;
    private List<DataExplorationAssetFieldVO> fields = new ArrayList<>();
    private List<DataExplorationAssetFieldVO> requestFields = new ArrayList<>();
    private List<DataExplorationAssetFieldVO> responseFields = new ArrayList<>();
}
