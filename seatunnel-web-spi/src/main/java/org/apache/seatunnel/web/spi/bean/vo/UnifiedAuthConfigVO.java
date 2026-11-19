package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

@Data
public class UnifiedAuthConfigVO {

    private String issuer;
    private String clientId;
    private String redirectUri;
    private boolean clientSecretConfigured;
    private boolean enabled;
    private boolean autoProvisionGeneralUser;
    private boolean ticketEnabled;
    private String authenticationMode;
    private boolean trustedCallbackConfigured;
    private String ticketValidationEndpoint;
    private String ticketValidationMethod;
    private String ticketLoginEntryUrl;
    private String ticketIssuer;
    private String ticketParameterName;
    private String ticketSuccessField;
    private String ticketSuccessValue;
    private String ticketUsernameField;
    private String ticketResultIssuerPointer;
    private String ticketResultSubjectPointer;
    private String ticketResultEmailPointer;
    private String ticketResultDisplayNamePointer;
    private Long configVersion;
}
