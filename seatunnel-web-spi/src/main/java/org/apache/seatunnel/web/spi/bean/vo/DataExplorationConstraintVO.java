package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Table-level constraint projection. */
@Data
public class DataExplorationConstraintVO {
    private String constraintType;
    private List<String> columns = new ArrayList<>();
    private List<String> referredColumns = new ArrayList<>();
    private String relationshipType;
}
