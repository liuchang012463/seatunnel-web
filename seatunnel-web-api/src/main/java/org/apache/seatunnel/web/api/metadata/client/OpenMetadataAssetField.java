package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/** One schema entry of an OpenMetadata asset. Search indexes may nest children. */
public record OpenMetadataAssetField(
        String name,
        String dataType,
        String description,
        List<String> tags,
        List<OpenMetadataAssetField> children) {

    public OpenMetadataAssetField {
        tags = tags == null ? List.of() : List.copyOf(tags);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public OpenMetadataAssetField(
            String name, String dataType, String description, List<String> tags) {
        this(name, dataType, description, tags, List.of());
    }
}
