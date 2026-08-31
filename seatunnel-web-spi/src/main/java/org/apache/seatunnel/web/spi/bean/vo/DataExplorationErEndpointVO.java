package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Endpoint of a foreign-key relationship. */
@Data
public class DataExplorationErEndpointVO {
    private String nodeId;
    private List<String> columns = new ArrayList<>();

    public DataExplorationErEndpointVO() {
    }

    public DataExplorationErEndpointVO(String nodeId, List<String> columns) {
        this.nodeId = nodeId;
        this.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
    }
}
