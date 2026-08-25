package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;

import java.util.Date;

/** Local, deliberately small control-plane record for one DataSource. */
@Data
@TableName("t_seatunnel_web_metadata_binding")
@EqualsAndHashCode(callSuper = true)
public class MetadataSourceBinding extends BaseEntity {

    @TableField("datasource_id")
    private Long dataSourceId;

    private MetadataDesiredState desiredState;

    private MetadataSyncStatus syncStatus;

    private Long configVersion;

    private Long syncedConfigVersion;

    private Long metadataTriggeredVersion;

    private String omServiceId;

    private String omServiceFqn;

    private String omMetadataPipelineId;

    private String omMetadataPipelineFqn;

    private String omProfilerPipelineId;

    private String omProfilerPipelineFqn;

    private MetadataRunStatus scanStatus;

    private Date scanLastRunTime;

    private Date scanLastSuccessTime;

    private String scanLastError;

    private MetadataRunStatus profileStatus;

    private Date profileLastRunTime;

    private Date profileLastSuccessTime;

    private String profileLastError;

    private Integer retryCount;

    private Date nextRetryTime;

    private String lastSyncErrorCode;

    private String lastSyncError;

    private Date lastStatusRefreshTime;

    private String statusRefreshError;

    private Long version;
}
