package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportOptions;

import java.util.Date;

/** Public view of one DMP/SQL offline import task. */
@Data
public class OfflineImportTaskVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;

    private String description;

    private String importType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileResourceId;

    private String fileName;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetDatasourceId;

    private String targetDatasourceName;

    private String targetSchema;

    private OfflineImportOptions options;

    /** PENDING / RUNNING / SUCCESS / FAILED. */
    private String status;

    private Integer totalStatements;

    private Integer executedStatements;

    private Integer failedStatements;

    private String errorMessage;

    private String executionLog;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;
}
