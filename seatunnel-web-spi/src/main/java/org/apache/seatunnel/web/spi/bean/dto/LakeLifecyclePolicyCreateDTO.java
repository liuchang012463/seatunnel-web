package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;

/** Create request for a reusable lifecycle policy. */
@Data
public class LakeLifecyclePolicyCreateDTO {

    @NotBlank
    @Size(max = 128)
    private String policyName;

    @NotNull
    private LakePartitionGranularity granularity;

    @NotNull
    @Positive
    private Integer retentionCount;

    @Size(max = 1024)
    /** Empty dimensions mean all; populated dimensions are combined with AND. */
    private String dataSourceType;
    private Long sourceDataSourceId;
    private Long unitId;
    private Long businessSystemId;

    private String description;

    private LakeLifecyclePolicyStatus status = LakeLifecyclePolicyStatus.DRAFT;
}
