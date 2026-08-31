package org.apache.seatunnel.web.api.lake.table;

import lombok.Data;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.enums.LakeTableModel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Persisted MANAGED table projection; credentials and raw SQL are absent. */
@Data
public class LakeManagedTableVO {

    private Long id;

    private Long sourceObjectRefId;

    private Long sourceDataSourceId;

    private String omEntityId;

    private String omFqn;

    private Long odsDatabaseBindingId;

    private Long lakeDataSourceId;

    private String databaseName;

    private String targetTableName;

    private LakeManagementLevel managementLevel;

    private LakeTableModel tableModel;

    private LakeResourceStatus resourceStatus;

    private Integer generation;

    private Integer lockVersion;

    private String sourceSchemaHash;

    private String targetContractHash;

    private String sourceSnapshotJson;

    private TargetContract targetContract;

    private List<LakeManagedTableFieldMapping> fieldMappings = new ArrayList<>();

    private LakeConsistencyStatus sourceConsistencyStatus;

    private LakeConsistencyStatus targetConsistencyStatus;

    private LakeConsistencyStatus taskConsistencyStatus;

    private Boolean actualTableExists;

    private String errorCode;

    private String errorMessage;

    private Date lastReconcileAt;

    private Integer createUserId;

    private Integer updateUserId;

    private Boolean deleted;

    private Date createTime;

    private Date updateTime;
}
