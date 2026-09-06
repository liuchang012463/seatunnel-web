package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Requested lifecycle policy for a read-through retention impact preview. */
@Data
public class LakeLifecycleRetentionPreviewDTO {

    @NotNull
    @Positive
    private Long policyId;
}
