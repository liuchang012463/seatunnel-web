package org.apache.seatunnel.web.api.metadata.client;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Minimal Table projection returned by OpenMetadata 1.12.10 APIs. */
@Data
public class OpenMetadataTable {

    private String id;
    private String name;
    private String displayName;
    private String fullyQualifiedName;
    private String tableType;
    private String description;
    private String retentionPeriod;
    private String serviceFullyQualifiedName;
    private String databaseFullyQualifiedName;
    private String schemaFullyQualifiedName;
    private List<OpenMetadataColumn> columns = new ArrayList<>();
    private List<OpenMetadataTableConstraint> tableConstraints = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private List<String> domains = new ArrayList<>();
    private OpenMetadataTableProfile profile;
}
