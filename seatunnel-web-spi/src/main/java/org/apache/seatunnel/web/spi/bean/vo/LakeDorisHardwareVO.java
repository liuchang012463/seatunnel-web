package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** Display-safe hardware summary collected from the Doris FE Home endpoint. */
@Data
public class LakeDorisHardwareVO {

    private String status;

    private String message;

    private String checkedAt;

    private String version;

    private String buildInfo;

    private String buildTime;

    private String hostName;

    private String ipv4;

    private String os;

    private String uptime;

    private String cpuModel;

    private Integer cpuCores;

    private String cpuLoad;

    private String memoryUsed;

    private String memoryTotal;

    private String memoryUsedPercent;

    private String swapUsed;

    private String swapTotal;

    private String filesystemFree;

    private String filesystemTotal;

    private String filesystemFreePercent;

    private Integer processCount;

    private Integer threadCount;

    private String diskSummary;

    private String networkReceive;

    private String networkTransmit;
}
