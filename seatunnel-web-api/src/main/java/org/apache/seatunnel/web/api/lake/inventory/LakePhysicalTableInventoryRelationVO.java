package org.apache.seatunnel.web.api.lake.inventory;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;

/** Safe summary of one persisted lake-job relation. */
@Data
public class LakePhysicalTableInventoryRelationVO {

    private Long relationId;

    private Long jobId;

    private LakeJobRuntimeType jobRuntimeType;

    private Integer jobVersion;

    private LakeRelationStatus relationStatus;

    private LakeRelationScope relationScope;

    private Long tableMappingId;

    private String sourceEndpointSnapshot;

    private String sinkEndpointSnapshot;

    private String schemaSaveModeSnapshot;
}
