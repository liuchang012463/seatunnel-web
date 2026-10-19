package org.apache.seatunnel.web.api.metadata.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema, sample payload and display properties of one OpenMetadata asset.
 *
 * <p>{@code samplePermissionDenied} and {@code sampleError} keep a failed sample read
 * from being confused with an asset that simply has no sample. API endpoints keep
 * request and response schemas separate; every other family uses {@code fields}.</p>
 */
public record OpenMetadataAssetDetail(
        OpenMetadataAsset asset,
        List<OpenMetadataAssetField> fields,
        List<OpenMetadataAssetField> requestFields,
        List<OpenMetadataAssetField> responseFields,
        OpenMetadataAssetSample sample,
        boolean samplePermissionDenied,
        boolean sampleError,
        List<OpenMetadataAsset> children,
        Map<String, Object> properties) {

    public OpenMetadataAssetDetail {
        fields = fields == null ? List.of() : List.copyOf(fields);
        requestFields = requestFields == null ? List.of() : List.copyOf(requestFields);
        responseFields = responseFields == null ? List.of() : List.copyOf(responseFields);
        children = children == null ? List.of() : List.copyOf(children);
        properties = properties == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
