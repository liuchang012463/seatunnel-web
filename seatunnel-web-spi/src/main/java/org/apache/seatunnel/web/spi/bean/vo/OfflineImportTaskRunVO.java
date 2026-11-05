package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.Date;

/** Public view of one persisted DMP/SQL import execution. */
@Data
public class OfflineImportTaskRunVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String taskName;

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
}
