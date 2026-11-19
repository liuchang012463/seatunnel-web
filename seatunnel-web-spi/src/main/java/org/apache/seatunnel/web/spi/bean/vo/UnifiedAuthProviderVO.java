package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

@Data
public class UnifiedAuthProviderVO {

    private boolean enabled;
    private boolean ticketLoginEnabled;
    private String displayName;
    private String authenticationMode;
    private String ticketLoginEntryUrl;
}
