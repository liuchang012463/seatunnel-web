package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "OpenMetadata 探查引擎连接配置")
public class OpenMetadataServerConfigDTO {

    private Boolean enabled;

    /** Must end with /api; never an Airflow endpoint. */
    private String baseUrl;

    /** Empty on update means keep the existing token. */
    private String token;

    private Integer connectTimeoutMs;

    private Integer readTimeoutMs;

    /** Optional Kingbase SSH tunnel host. */
    private String kingbaseTunnelHost;

    /** Optional Kingbase SSH tunnel port; 0 means unused. */
    private Integer kingbaseTunnelPort;
}
