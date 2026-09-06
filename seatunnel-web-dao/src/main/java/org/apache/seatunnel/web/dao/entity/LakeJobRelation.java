package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;

@Data
@TableName("t_seatunnel_web_lake_job_relation")
@EqualsAndHashCode(callSuper = true)
public class LakeJobRelation extends BaseEntity {

    private Long odsDatabaseBindingId;

    private Long tableMappingId;

    private LakeRelationScope relationScope;

    private LakeJobRuntimeType jobRuntimeType;

    private Long jobId;

    private Integer jobVersion;

    private LakeRelationStatus relationStatus;

    private String sourceEndpointSnapshot;

    private String sinkEndpointSnapshot;

    private String schemaSaveModeSnapshot;
}
