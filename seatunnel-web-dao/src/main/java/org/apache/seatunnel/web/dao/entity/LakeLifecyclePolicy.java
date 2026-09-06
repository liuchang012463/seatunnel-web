package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;

@Data
@TableName("t_seatunnel_web_lake_lifecycle_policy")
@EqualsAndHashCode(callSuper = true)
public class LakeLifecyclePolicy extends BaseEntity {

    private String policyName;

    private Integer version = 1;

    private LakeLifecyclePolicyStatus status;

    private LakePartitionGranularity granularity;

    private Integer retentionCount;

    private String description;

    private Integer createUserId;

    private Integer updateUserId;
}
