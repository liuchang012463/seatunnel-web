package org.apache.seatunnel.web.spi.bean.dto;

import lombok.Data;

@Data
public class UnifiedAuthConfigDTO {

    private String issuer;
    private String clientId;
    private String redirectUri;
    private String clientSecret;
    private Boolean enabled;
    private Boolean autoProvisionGeneralUser;
    private Boolean ticketEnabled;
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
}
