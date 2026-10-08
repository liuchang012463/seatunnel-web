package org.apache.seatunnel.web.api.metadata.client;

import java.util.List;

/**
 * Schema and sample payload of one non-relational OpenMetadata asset. Sample rows and
 * messages are empty when the ingestion pipeline has never collected them, or when the
 * caller is not allowed to view sample data.
 *
 * <p>API endpoints keep their request and response schemas apart; every other asset
 * family uses {@code fields}.</p>
 */
public record OpenMetadataResourceDetail(
        OpenMetadataResource resource,
        List<OpenMetadataResourceField> fields,
        List<OpenMetadataResourceField> requestFields,
        List<OpenMetadataResourceField> responseFields,
        boolean sampleDataAvailable,
        List<String> sampleColumns,
        List<List<String>> sampleRows,
        List<String> messages) {

    /** Convenience for the families that carry a single schema list. */
    public OpenMetadataResourceDetail(
            OpenMetadataResource resource,
            List<OpenMetadataResourceField> fields,
            boolean sampleDataAvailable,
            List<String> sampleColumns,
            List<List<String>> sampleRows,
            List<String> messages) {
        this(resource, fields, List.of(), List.of(), sampleDataAvailable, sampleColumns, sampleRows, messages);
    }
}
