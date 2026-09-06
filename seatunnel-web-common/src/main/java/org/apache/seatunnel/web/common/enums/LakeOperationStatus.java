package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum LakeOperationStatus {
    PENDING("PENDING"),
    RUNNING("RUNNING"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    IGNORED("IGNORED");

    @EnumValue
    private final String code;
}
