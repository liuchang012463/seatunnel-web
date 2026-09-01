package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Read-through lifecycle eligibility validation request. */
@Data
public class LakeLifecycleValidateDTO {

    @NotNull
    @Positive
    private Long mappingId;

    @NotNull
    @Positive
    private Long policyId;
}
