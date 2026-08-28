package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** ER diagram projection assembled from OpenMetadata table constraints. */
@Data
public class DataExplorationErDiagramVO {
    /** Matches the open_metadata_extension ERDiagram response contract. */
    private String databaseFqn;
    private String schemaFullyQualifiedName;
    private List<DataExplorationErNodeVO> nodes = new ArrayList<>();
    private List<DataExplorationErEdgeVO> edges = new ArrayList<>();
}
