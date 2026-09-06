package org.apache.seatunnel.web.api.lake.lifecycle;

import lombok.Data;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;

import java.util.Date;

/** Safe, read-only projection of a persisted table mapping. */
@Data
public class LakeLifecycleMappingSnapshotVO {

    private Long id;
    private Long sourceObjectRefId;
    private Long odsDatabaseBindingId;
    private Long lakeDataSourceId;
    private String databaseName;
    private String targetTableName;
    private LakeManagementLevel managementLevel;
    private LakeResourceStatus resourceStatus;
    private Integer generation;
    private Integer lockVersion;
    private String sourceSchemaHash;
    private String sourceSnapshotJson;
    private String targetContractHash;
    private TargetContract targetContract;
    private LakeConsistencyStatus sourceConsistencyStatus;
    private LakeConsistencyStatus targetConsistencyStatus;
    private LakeConsistencyStatus taskConsistencyStatus;
    private Boolean actualTableExists;
    private Date lastReconcileAt;
    private Integer createUserId;
    private Integer updateUserId;
    private Boolean deleted;
    private Date createTime;
    private Date updateTime;
}
