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

    /** Operator decision for OpenMetadata sample-data collection; off by default. */
    private Boolean sampleDataEnabled;

    /**
     * OpenMetadata object-storage manifest (the {@code defaultManifest} JSON) used to
     * derive a container data model; OpenMetadata-only, never part of a SeaTunnel job.
     */
    private String storageManifestConfig;

    /**
     * Run id of the metadata scan the object-storage sample agent was last triggered for.
     * The id is stable, unlike the recomputed scan success time, which drifts between
     * status refreshes.
     */
    private String storageSampleScanRunId;

    private MetadataRunStatus scanStatus;

    private Date scanLastRunTime;

    private Date scanLastSuccessTime;

    private String scanLastError;

    private MetadataRunStatus profileStatus;

    private Date profileLastRunTime;

    private Date profileLastSuccessTime;

    private String profileLastError;

    private String profileRunReservationToken;

    private String profileProfilerRunIdBaseline;

    private String profileSampleRunIdBaseline;

    private Boolean profileRunBaselineCaptured;

    private Date profileRunBaselineCapturedAt;

    private Integer retryCount;

    private Date nextRetryTime;

    private String lastSyncErrorCode;

    private String lastSyncError;

    private Date lastStatusRefreshTime;

    private String statusRefreshError;

    private Long version;
}
