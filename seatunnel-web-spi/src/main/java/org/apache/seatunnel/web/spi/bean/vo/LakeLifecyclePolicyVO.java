package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;

import java.util.Date;

/** Safe lifecycle policy projection; bindings retain their own snapshots. */
@Data
public class LakeLifecyclePolicyVO {

    private Long id;
    private String policyName;
    private Integer version;
    private LakeLifecyclePolicyStatus status;
    private LakePartitionGranularity granularity;
    private Integer retentionCount;
    /** Empty dimensions mean all; populated dimensions are combined with AND. */
    private String dataSourceType;
    private Long sourceDataSourceId;
    private Long unitId;
    private Long businessSystemId;

    private String scopeDescription;
    private String description;
    private Integer createUserId;
    private Integer updateUserId;
    private Date createTime;
    private Date updateTime;
}
