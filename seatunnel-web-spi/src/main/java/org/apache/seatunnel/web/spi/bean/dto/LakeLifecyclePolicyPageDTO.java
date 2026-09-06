package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.spi.bean.dto.pagination.PaginationBaseDTO;

/** Query request for reusable lifecycle policies. */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Lifecycle policy page request")
public class LakeLifecyclePolicyPageDTO extends PaginationBaseDTO {

    @Size(max = 128)
    private String policyName;

    private LakeLifecyclePolicyStatus status;

    private LakePartitionGranularity granularity;
}
