package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/** One schema entry of a non-relational OpenMetadata asset. */
public record OpenMetadataResourceField(
        String name, String dataType, String description, List<String> tags) {
}
