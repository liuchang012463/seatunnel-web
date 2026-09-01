package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Request to apply an active lifecycle policy to a managed table. */
@Data
public class LakeLifecycleApplyDTO {

    @NotNull
    @Positive
    private Long mappingId;

    @NotNull
    @Positive
    private Long policyId;
}
