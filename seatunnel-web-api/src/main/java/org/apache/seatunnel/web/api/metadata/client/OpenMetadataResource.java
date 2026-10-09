package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/**
 * One non-relational OpenMetadata asset (topic, container, file, API, index) as it
 * appears in a collection listing. Sample payloads are read through the detail call.
 *
 * <p>{@code fieldCount} is the size of the asset's own schema; {@code childCount} is the
 * number of assets it contains (an API collection's endpoints). Exactly one is usually
 * set.</p>
 */
public record OpenMetadataResource(
        String id,
        String name,
        String fullyQualifiedName,
        String entityType,
        String description,
        Integer fieldCount,
        Integer childCount,
        List<String> tags,
        String serviceFullyQualifiedName) {

    /** Convenience for the families whose listing has no contained assets. */
    public OpenMetadataResource(
            String id,
            String name,
            String fullyQualifiedName,
            String entityType,
            String description,
            Integer fieldCount,
            List<String> tags,
            String serviceFullyQualifiedName) {
        this(id, name, fullyQualifiedName, entityType, description, fieldCount, null, tags,
                serviceFullyQualifiedName);
    }
}
