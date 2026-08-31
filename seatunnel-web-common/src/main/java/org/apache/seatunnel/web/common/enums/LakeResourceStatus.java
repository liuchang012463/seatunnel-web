package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Lifecycle of a resource whose physical state is maintained outside Web. */
@AllArgsConstructor
@Getter
public enum LakeResourceStatus {
    PENDING_CREATE("PENDING_CREATE"),
    CREATING("CREATING"),
    READY("READY"),
    ERROR("ERROR"),
    CREATE_FAILED("CREATE_FAILED"),
    MISSING("MISSING"),
    DELETING("DELETING"),
    DELETED("DELETED");

    @EnumValue
    private final String code;
}
