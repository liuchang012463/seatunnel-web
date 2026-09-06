package org.apache.seatunnel.web.api.lake.lifecycle;

import lombok.Data;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Result of the explicit, read-through lifecycle eligibility validation. */
@Data
public class LakeLifecycleValidateVO {

    private boolean valid;

    /** Stable primary result code; no remote exception text is returned. */
    private String code;

    /** Stable reason codes in evaluation order. */
    private List<String> reasons = new ArrayList<>();

    private Long mappingId;
    private Long policyId;
    private LakeLifecycleMappingSnapshotVO mappingSnapshot;
    private LakeLifecyclePolicyVO policySnapshot;
    private String partitionColumn;
    private LakePartitionGranularity granularity;
    private Integer desiredRetentionCount;
    private Integer actualRetentionCount;
    private Boolean structuralMatch;
    private DorisPartitionSummary partitionSummary;
    private Instant observedAt;
    private LakeLifecycleBindingSnapshotVO existingBinding;
    private Boolean existingBindingPolicyDiff;

    /** Alias for clients that use the evaluator terminology. */
    public String getReasonCode() {
        return code;
    }

    /** Alias for callers that refer to the selected policy as {@code policy}. */
    public LakeLifecyclePolicyVO getPolicy() {
        return policySnapshot;
    }

    /** Alias for callers that refer to the mapping as {@code mapping}. */
    public LakeLifecycleMappingSnapshotVO getMapping() {
        return mappingSnapshot;
    }
}
