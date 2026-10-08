package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/**
 * One non-relational OpenMetadata asset (topic, container, file, API, index) as it
 * appears in a collection listing. Sample payloads are read through the detail call.
 */
public record OpenMetadataResource(
        String id,
        String name,
        String fullyQualifiedName,
        String entityType,
        String description,
        Integer fieldCount,
        List<String> tags,
        String serviceFullyQualifiedName) {
}
