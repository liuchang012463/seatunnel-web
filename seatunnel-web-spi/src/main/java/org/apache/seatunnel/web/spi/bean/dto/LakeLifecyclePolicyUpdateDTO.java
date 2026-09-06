package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;

/** Full optimistic-concurrency update request for a lifecycle policy. */
@Data
public class LakeLifecyclePolicyUpdateDTO {

    @NotBlank
    @Size(max = 128)
    private String policyName;

    @NotNull
    private LakePartitionGranularity granularity;

    @NotNull
    @Positive
    private Integer retentionCount;

    @Size(max = 1024)
    private String description;

    @NotNull
    private LakeLifecyclePolicyStatus status;

    @NotNull
    @Positive
    private Integer expectedVersion;
}
