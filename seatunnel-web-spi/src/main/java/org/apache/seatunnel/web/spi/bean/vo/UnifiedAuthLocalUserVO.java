package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;
import org.apache.seatunnel.web.common.enums.UserType;

@Data
public class UnifiedAuthLocalUserVO {

    private Integer id;
    private String userName;
    private String email;
    private UserType userType;
}
