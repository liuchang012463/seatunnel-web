package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;

import java.util.Date;

/** Read-only latest OpenMetadata PipelineStatus item; no Airflow fields are exposed. */
@Data
public class MetadataPipelineRunVO {

    private String runId;

    private MetadataRunStatus status;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;

    private Integer warningsCount;
}
