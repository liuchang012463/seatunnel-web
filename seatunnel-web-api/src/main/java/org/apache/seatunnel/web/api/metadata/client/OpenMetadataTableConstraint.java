package org.apache.seatunnel.web.api.metadata.client;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Table-level constraint from the OpenMetadata 1.12.10 Table entity. */
@Data
public class OpenMetadataTableConstraint {

    private String constraintType;
    private List<String> columns = new ArrayList<>();
    private List<String> referredColumns = new ArrayList<>();
    private String relationshipType;
}
