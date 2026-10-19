package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Sample payload stored in OpenMetadata for an exploration asset. */
@Data
public class DataExplorationAssetSampleVO {
    private String kind;
    private String status;
    private List<String> columns = new ArrayList<>();
    private List<List<String>> rows = new ArrayList<>();
    private List<String> messages = new ArrayList<>();
    private int messageCount;
}
