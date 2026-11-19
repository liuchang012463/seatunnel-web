package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.UserType;

import java.util.Date;

@Data
public class UnifiedAuthIdentityVO {

    private Long identityId;
    private String identitySource;
    private String issuer;
    private String subject;
    private String email;
    private String displayName;
    private Integer localUserId;
    private String localUserName;
    private UserType localUserType;
    private Date lastObservedAt;
}
