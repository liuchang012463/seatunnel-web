package org.apache.seatunnel.web.api.metadata.client;

import lombok.Data;

/** Minimal, read-only DatabaseSchema projection used by the exploration facade. */
@Data
public class OpenMetadataDatabaseSchema {

    private String id;
    private String name;
    private String fullyQualifiedName;
    private String databaseFullyQualifiedName;
    private String serviceFullyQualifiedName;
}
