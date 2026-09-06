package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum LakeTableModel {
    DUPLICATE("DUPLICATE"),
    UNIQUE("UNIQUE");

    @EnumValue
    private final String code;
}
