package org.apache.seatunnel.web.api.lake.lifecycle;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Read-through lifecycle retention impact preview. */
@Data
public class LakeLifecycleRetentionPreviewVO {

    private boolean valid;
    private String code;
    private List<String> reasons = new ArrayList<>();
    private Long mappingId;
    private Long policyId;
    private LakeLifecycleMappingSnapshotVO mappingSnapshot;
    private LakeLifecyclePolicyVO requestedPolicySnapshot;
    private LakeLifecycleBindingSnapshotVO existingBinding;
    private Integer currentDesiredRetentionCount;
    private Integer currentActualRetentionCount;
    private Integer requestedRetentionCount;
    private Integer historicalPartitionCount;
    private List<String> impactedHistoricalPartitionNames = new ArrayList<>();
    private Integer impactedHistoricalPartitionCount;
    private boolean requiresConfirmation;
    private String planFingerprint;

    /** @deprecated use {@link #planFingerprint}; retained for old clients only. */
    @Deprecated
    @JsonIgnore
    private String confirmationToken;
    private DorisPartitionSummary partitionSummary;
    private Instant observedAt;

    public LakeLifecyclePolicyVO getPolicySnapshot() {
        return requestedPolicySnapshot;
    }

    public List<String> getImpactedPartitionNames() {
        return impactedHistoricalPartitionNames;
    }
}
