package org.apache.seatunnel.web.api.metadata;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.seatunnel.web.spi.enums.ExplorationQualityStatus;

/** Result of the deliberately small schema-constraint/profile evaluator. */
@Data
@AllArgsConstructor
public class ExplorationQualityResult {
    private ExplorationQualityStatus qualityStatus;
    private String qualityReason;
}
