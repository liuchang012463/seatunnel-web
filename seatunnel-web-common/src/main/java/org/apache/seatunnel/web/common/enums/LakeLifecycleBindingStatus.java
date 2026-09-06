package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum LakeLifecycleBindingStatus {
    PENDING("PENDING"),
    ACTIVE("ACTIVE"),
    ERROR("ERROR"),
    DISABLED("DISABLED");

    @EnumValue
    private final String code;
}
