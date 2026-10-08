package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/**
 * Schema and sample payload of one non-relational OpenMetadata asset. Sample rows and
 * messages are empty when the ingestion pipeline has never collected them, or when the
 * caller is not allowed to view sample data.
 */
public record OpenMetadataResourceDetail(
        OpenMetadataResource resource,
        List<OpenMetadataResourceField> fields,
        boolean sampleDataAvailable,
        List<String> sampleColumns,
        List<List<String>> sampleRows,
        List<String> messages) {
}
