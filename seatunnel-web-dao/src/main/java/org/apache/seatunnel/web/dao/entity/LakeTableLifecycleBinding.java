package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;

import java.util.Date;

@Data
@TableName("t_seatunnel_web_lake_table_lifecycle_binding")
@EqualsAndHashCode(callSuper = true)
public class LakeTableLifecycleBinding extends BaseEntity {

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

    private String operationToken;

    private Integer generation = 1;

    private Integer lockVersion = 1;

    private String errorCode;

    private String errorMessage;

    private Integer createUserId;

    private Integer updateUserId;
}
