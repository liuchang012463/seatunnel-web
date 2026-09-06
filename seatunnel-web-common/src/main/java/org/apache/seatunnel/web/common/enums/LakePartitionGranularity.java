package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum LakePartitionGranularity {
    DAY("DAY"),
    MONTH("MONTH"),
    YEAR("YEAR");

    @EnumValue
    private final String code;
}
