package org.apache.seatunnel.web.api.lake.table;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;

/** Safe summary of one job relation shown before a destructive operation. */
@Data
public class LakeManagedTableRelationImpactVO {

    private Long relationId;

    private Long jobId;

    private Integer jobVersion;

    private LakeRelationScope relationScope;

    private LakeJobRuntimeType jobRuntimeType;

    private LakeRelationStatus relationStatus;
}
