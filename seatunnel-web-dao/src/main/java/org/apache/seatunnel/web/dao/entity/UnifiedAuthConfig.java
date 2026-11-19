package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.util.Date;

/** Stored OIDC and external-ticket authentication configuration. */
@Data
@TableName("t_seatunnel_web_unified_auth_config")
public class UnifiedAuthConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;
    private String issuer;
    private String clientId;
    private String redirectUri;
    @ToString.Exclude
    private String clientSecretEnc;
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
    private Long configVersion;
    private Date createTime;
    private Date updateTime;
}
