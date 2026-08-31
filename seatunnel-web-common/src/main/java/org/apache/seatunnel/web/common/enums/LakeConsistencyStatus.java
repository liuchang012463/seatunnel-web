package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Independent source, target and task consistency result. */
@AllArgsConstructor
@Getter
public enum LakeConsistencyStatus {
    CONSISTENT("CONSISTENT"),
    DRIFT("DRIFT"),
    MISSING("MISSING"),
    UNKNOWN("UNKNOWN"),
    UNBOUND("UNBOUND");

    @EnumValue
    private final String code;
}
