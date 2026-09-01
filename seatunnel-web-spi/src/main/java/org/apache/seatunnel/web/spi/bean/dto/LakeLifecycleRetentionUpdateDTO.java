package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Request to change the frozen lifecycle desired retention for a table. */
@Data
public class LakeLifecycleRetentionUpdateDTO {

    @NotNull
    @Positive
    private Long policyId;

    /** Required only when the requested retention decreases. */
    private String confirmationToken;
}
