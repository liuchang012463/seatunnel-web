package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

@Data
public class OpenMetadataServerConfigVO {

    private boolean enabled;

    private String baseUrl;

    private boolean tokenConfigured;

    private Integer connectTimeoutMs;

    private Integer readTimeoutMs;

    private String expectedServerVersion;

    private String expectedIngestionPatch;

    private String kingbaseTunnelHost;

    private Integer kingbaseTunnelPort;

    private Long configVersion;

    private String connStatus;

    private String lastError;

    private boolean configured;

    /** Optional embedded health snapshot for the ops overview page. */
    private MetadataIntegrationHealthVO health;
}
