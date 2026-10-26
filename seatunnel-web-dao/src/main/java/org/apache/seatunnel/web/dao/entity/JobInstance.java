package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.RunMode;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
@TableName("t_seatunnel_web_job_instance")
public class JobInstance {

    @TableId(type = IdType.INPUT)
    private Long id;

    /**
     * Associated job definition id
     */
    private Long jobDefinitionId;

    /**
     * Run mode, such as MANUAL / SCHEDULE / RETRY
     */
    private RunMode runMode;

    /**
     * Job mode, such as BATCH / STREAMING
     */
    private JobMode jobMode;

    /**
     * Current job execution status
     */
    private JobStatus jobStatus;

    /**
     * Trigger source description
     */
    private String triggerSource;

    /**
     * Retry count of current instance
     */
    private Integer retryCount;

    private Long clientId;

    /**
     * Engine-side job id. The web reserves it before submission so a lost submit response can be
     * reconciled without asking the user or submitting a duplicate job.
     */
    private String engineJobId;

    /**
     * Runtime config used by this execution
     */
    private String runtimeConfig;

    /**
     * Log file path
     */
    private String logPath;

    /**
     * Job name captured when this instance was submitted: renaming the definition afterwards must
     * not split one run across two lineage jobs.
     */
    private String jobName;

    /**
     * Source/target identifiers captured when this instance was submitted. They are a snapshot:
     * editing the definition afterwards must not change what lineage is written for this run.
     */
    private Long sourceDatasourceId;

    private Long sinkDatasourceId;

    private String sourceTable;

    private String sinkTable;

    /**
     * Error summary message
     */
    private String errorMessage;

    /**
     * Submit time to engine
     */
    private Date submitTime;

    /**
     * Actual start time
     */
    private Date startTime;

    /**
     * Finish time
     */
    private Date endTime;

    private Date createTime;

    private Date updateTime;
}
