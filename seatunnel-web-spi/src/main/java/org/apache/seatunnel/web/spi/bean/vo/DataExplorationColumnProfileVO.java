package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.seatunnel.web.spi.enums.ExplorationQualityStatus;

import java.math.BigDecimal;

/** Known column metrics mapped from the OpenMetadata 1.12.10 profile contract. */
@Data
public class DataExplorationColumnProfileVO {
    private String name;
    private String dataType;
    private String constraint;
    private Long profileTime;
    private Long valuesCount;
    private Long validCount;
    private Long duplicateCount;
    private Long nullCount;
    private Long missingCount;
    private Long distinctCount;
    private Long uniqueCount;
    private BigDecimal nullProportion;
    private BigDecimal distinctProportion;
    private BigDecimal uniqueProportion;
    private JsonNode min;
    private JsonNode max;
    private BigDecimal mean;
    private Long minLength;
    private Long maxLength;
    private ExplorationQualityStatus qualityStatus;
    private String qualityReason;
}
