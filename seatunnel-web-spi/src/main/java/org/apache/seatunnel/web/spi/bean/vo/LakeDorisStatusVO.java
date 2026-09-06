package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Display-safe status snapshot for the Doris cluster behind the lake. */
@Data
public class LakeDorisStatusVO {

    private boolean configured;

    private String status;

    private String message;

    private String version;

    private Integer frontendCount;

    private Integer aliveFrontendCount;

    private Integer backendCount;

    private Integer aliveBackendCount;

    private Integer databaseCount;

    private String masterHost;

    private String queryPort;

    private String httpPort;

    private String checkedAt;

    private List<LakeDorisNodeVO> frontends = new ArrayList<>();

    private List<LakeDorisNodeVO> backends = new ArrayList<>();
}
