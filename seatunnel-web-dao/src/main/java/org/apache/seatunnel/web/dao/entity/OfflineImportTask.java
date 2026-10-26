package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** One DMP/SQL offline import task executed by the web backend. */
@Data
@TableName("t_seatunnel_web_offline_import_task")
@EqualsAndHashCode(callSuper = true)
public class OfflineImportTask extends BaseEntity {

    private String name;

    private String description;

    /** One of {@link org.apache.seatunnel.web.spi.enums.OfflineImportType}. */
    private String importType;

    private Long fileResourceId;

    private String fileName;

    private Long targetDatasourceId;

    private String targetSchema;

    private String optionsJson;

    /** PENDING / QUEUED / RUNNING / SUCCESS / FAILED. */
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
