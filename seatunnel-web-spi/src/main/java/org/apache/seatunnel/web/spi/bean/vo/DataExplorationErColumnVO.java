package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Column node data and normalized constraint labels for the ER canvas. */
@Data
public class DataExplorationErColumnVO {
    private String id;
    private String name;
    private String displayName;
    private String description;
    private String dataType;
    private List<String> constraints = new ArrayList<>();
}
