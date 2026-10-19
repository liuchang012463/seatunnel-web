package org.apache.seatunnel.web.api.metadata.client;

import org.apache.seatunnel.web.api.metadata.ExplorationAssetType;

import java.util.List;

/**
 * Sample payload collected by OpenMetadata. Messages are used by topics and search
 * indexes; columns/rows are used by containers and files.
 */
public record OpenMetadataAssetSample(
        ExplorationAssetType.SampleKind kind,
        List<String> columns,
        List<List<String>> rows,
        List<String> messages) {

    public OpenMetadataAssetSample {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static OpenMetadataAssetSample messages(List<String> messages) {
        return new OpenMetadataAssetSample(
                ExplorationAssetType.SampleKind.MESSAGES, List.of(), List.of(), messages);
    }

    public static OpenMetadataAssetSample rows(List<String> columns, List<List<String>> rows) {
        return new OpenMetadataAssetSample(
                ExplorationAssetType.SampleKind.ROWS, columns, rows, List.of());
    }

    public boolean isEmpty() {
        return columns.isEmpty() && rows.isEmpty() && messages.isEmpty();
    }
}
