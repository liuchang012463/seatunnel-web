package org.apache.seatunnel.web.spi.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;

import java.util.Date;

@Data
public class MetadataRunStateVO {

    private MetadataRunStatus status;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date lastRunTime;

    @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss", timezone = "GMT+8")
    private Date lastSuccessTime;

    private String lastError;
}
