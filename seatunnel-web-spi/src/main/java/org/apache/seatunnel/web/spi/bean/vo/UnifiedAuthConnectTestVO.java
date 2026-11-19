package org.apache.seatunnel.web.spi.bean.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UnifiedAuthConnectTestVO {

    private boolean connected;
    private String message;
}
