package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeTableModel;

@Data
@TableName("t_seatunnel_web_lake_ods_table_mapping")
@EqualsAndHashCode(callSuper = true)
public class LakeOdsTableMapping extends LakeResourceEntity {

    private Long sourceObjectRefId;

    private Long odsDatabaseBindingId;

    private Long lakeDataSourceId;

    private String databaseName;

    private String targetTableName;

    private LakeManagementLevel managementLevel;

    private LakeTableModel tableModel;

    private String sourceSchemaHash;

    private String targetContractHash;

    private String sourceSnapshotJson;

    private String targetContractJson;

    /** Last explicitly observed, secret-free Doris structural contract. */
    private String actualContractJson;

    private String fieldMappingsJson;

    private LakeConsistencyStatus sourceConsistencyStatus;

    private LakeConsistencyStatus targetConsistencyStatus;

    private LakeConsistencyStatus taskConsistencyStatus;

    private Boolean actualTableExists;
}
