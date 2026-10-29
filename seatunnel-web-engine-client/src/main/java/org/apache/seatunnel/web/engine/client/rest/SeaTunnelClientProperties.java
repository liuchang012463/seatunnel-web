package org.apache.seatunnel.web.engine.client.rest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "seatunnel.client")
public class SeaTunnelClientProperties {


    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 60000;

    /** Dedicated timeout for config uploads, which can take longer while the Engine resolves connectors. */
    private int jobSubmitReadTimeoutMs = 300000;

}
