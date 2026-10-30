package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/**
 * Summary of one OpenMetadata asset (topic, index, container, directory, file, API
 * collection or endpoint) as it appears in a collection listing.
 *
 * <p>Exactly one of {@code fieldCount} and {@code childCount} is usually set.
 * The parent id/type/fqn are null at the root level. Directory children built from an
 * {@code EntityReference} legitimately have null tags/size/updatedAt.</p>
 */
public record OpenMetadataAsset(
        String id,
        String name,
        String displayName,
        String fullyQualifiedName,
        String entityType,
        String description,
        Integer fieldCount,
        Integer childCount,
        List<String> tags,
        String serviceFullyQualifiedName,
        String parentId,
        String parentType,
        String parentFullyQualifiedName,
        Long sizeInByte,
        Long updatedAt,
        String requestMethod,
        String endpointURL) {

    public OpenMetadataAsset {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
