package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Runtime family of a structured SeaTunnel job. */
@AllArgsConstructor
@Getter
public enum LakeJobRuntimeType {
    BATCH("BATCH"),
    STREAMING("STREAMING");

    @EnumValue
    private final String code;
}
