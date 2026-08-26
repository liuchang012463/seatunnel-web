package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Full transient table detail mapped from OpenMetadata. */
@Data
public class DataExplorationTableDetailVO {
    private String id;
    private String name;
    private String fullyQualifiedName;
    private String tableType;
    private String description;
    private String serviceFullyQualifiedName;
    private String databaseFullyQualifiedName;
    private String schemaFullyQualifiedName;
    private List<DataExplorationColumnVO> columns = new ArrayList<>();
    private List<DataExplorationConstraintVO> tableConstraints = new ArrayList<>();
}
