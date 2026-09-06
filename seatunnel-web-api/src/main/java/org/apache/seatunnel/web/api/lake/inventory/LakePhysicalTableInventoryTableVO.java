package org.apache.seatunnel.web.api.lake.inventory;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

/** Safe table summary used by the physical ODS inventory response. */
@Data
public class LakePhysicalTableInventoryTableVO {

    private Long mappingId;

    private Long sourceObjectRefId;

    private String targetTableName;

    private LakeManagementLevel managementLevel;

    private LakeResourceStatus resourceStatus;

    private Boolean sourceBound;

    private Boolean actualExists;
}
