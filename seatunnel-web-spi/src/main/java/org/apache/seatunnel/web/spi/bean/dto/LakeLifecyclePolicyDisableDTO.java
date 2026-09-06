package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Explicit lifecycle-policy disable command. */
@Data
public class LakeLifecyclePolicyDisableDTO {

    @NotNull
    @Positive
    private Integer expectedVersion;
}
