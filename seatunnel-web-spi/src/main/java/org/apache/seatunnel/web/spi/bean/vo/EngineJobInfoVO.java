package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Whitelisted SeaTunnel Engine job-info fields shown in a batch instance detail view. */
@Data
public class EngineJobInfoVO {

    private Long instanceId;

    private String engineJobId;

    private String localStatus;

    private String localErrorMessage;

    /** False when the Engine could not be reached, so only the local fields are populated. */
    private Boolean engineAvailable;

    private String status;

    private Object createTime;

    private Object finishTime;

    private String errorMsg;

    private Object jobDag;

    private Object diagnostics;

    private Object metrics;
}
