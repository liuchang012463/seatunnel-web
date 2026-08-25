package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Desired lifecycle state of the local OpenMetadata binding. */
@AllArgsConstructor
@Getter
public enum MetadataDesiredState {
    ACTIVE("ACTIVE"),
    DELETED("DELETED");

    @EnumValue
    private final String code;
}
