package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** One durable execution record for a DMP/SQL offline import task. */
@Data
@TableName("t_seatunnel_web_offline_import_task_run")
@EqualsAndHashCode(callSuper = true)
public class OfflineImportTaskRun extends BaseEntity {

    private Long taskId;

    private String taskName;

    private String status;

    private Integer totalStatements;

    private Integer executedStatements;

    private Integer failedStatements;

    private String errorMessage;

    private String executionLog;

    private Date startTime;

    private Date endTime;

    private Integer createUserId;
}
