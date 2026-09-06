package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum LakeCatalogScope {
    ALL("ALL"),
    DATABASE("DATABASE"),
    TABLE("TABLE");

    @EnumValue
    private final String code;
}
