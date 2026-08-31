package org.apache.seatunnel.web.api.metadata.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Known OpenMetadata 1.12.10 column-profile metrics. Unknown metrics are
 * intentionally not represented and therefore cannot be fabricated as zeroes.
 */
@Data
public class OpenMetadataColumnProfile {

    private String name;
    private Long timestamp;
    private Long valuesCount;
    private Long validCount;
    private Long duplicateCount;
    private Long nullCount;
    private Long missingCount;
    private Long uniqueCount;
    private Long distinctCount;
    private JsonNode min;
    private JsonNode max;
    private Long minLength;
    private Long maxLength;
    private java.math.BigDecimal mean;
    private java.math.BigDecimal nullProportion;
    private java.math.BigDecimal distinctProportion;
    private java.math.BigDecimal uniqueProportion;
    private java.math.BigDecimal valuesPercentage;
    private java.math.BigDecimal missingPercentage;
    private java.math.BigDecimal sum;
    private java.math.BigDecimal stddev;
    private java.math.BigDecimal variance;
    private java.math.BigDecimal median;
}
