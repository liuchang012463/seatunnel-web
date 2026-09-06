package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** A display-safe snapshot of one Doris FE or BE node. */
@Data
public class LakeDorisNodeVO {

    private String id;

    private String host;

    private String port;

    private String role;

    private String status;

    private String version;

    private String lastHeartbeat;

    private String usedPct;
}
