package org.apache.seatunnel.web.api.lake.lifecycle;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;

import java.util.Date;

/** Cached lifecycle binding projection; operation tokens are intentionally omitted. */
@Data
public class LakeLifecycleBindingSnapshotVO {

    private Long id;
    private Long tableMappingId;
    private Long policyId;
    private Integer policyVersion;
    private String partitionColumn;
    private LakePartitionGranularity granularity;
    private Integer retentionCount;
    private Integer actualRetentionCount;
    private String actualPartitionSummaryJson;
    private Date lastObservedAt;
    private String policySnapshotJson;
    private LakeLifecycleBindingStatus status;
    private Integer generation;
    private Integer lockVersion;
    private String errorCode;
    private Date createTime;
    private Date updateTime;
}
