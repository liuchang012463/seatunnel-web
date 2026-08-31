package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum LakeLifecyclePolicyStatus {
    DRAFT("DRAFT"),
    ACTIVE("ACTIVE"),
    DISABLED("DISABLED");

    @EnumValue
    private final String code;
}
