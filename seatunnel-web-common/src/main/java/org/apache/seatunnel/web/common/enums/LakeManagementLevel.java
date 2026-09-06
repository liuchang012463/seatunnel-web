package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Whether Web owns the structural contract for an ODS table. */
@AllArgsConstructor
@Getter
public enum LakeManagementLevel {
    MANAGED("MANAGED"),
    AUTO_CREATED("AUTO_CREATED"),
    UNMANAGED("UNMANAGED");

    @EnumValue
    private final String code;
}
