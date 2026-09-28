package org.apache.seatunnel.web.engine.client.modal;

import lombok.Data;

/**
 * Authentication configuration for SeaTunnel client.
 */
@Data
public class SeaTunnelClientAuth {

    /**
     * Whether authentication is enabled.
     */
    private Boolean authEnabled;

    /**
     * Username for authentication.
     */
    private String username;

    /**
     * Password for authentication.
     */
    private String password;
}
