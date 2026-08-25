package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Cached latest status of a metadata scan or profiler run. */
@AllArgsConstructor
@Getter
public enum MetadataRunStatus {
    NEVER("NEVER"),
    QUEUED("QUEUED"),
    RUNNING("RUNNING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    UNKNOWN("UNKNOWN");

    @EnumValue
    private final String code;
}
