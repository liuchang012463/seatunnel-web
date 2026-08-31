package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Local control-plane status for synchronizing a binding to OpenMetadata. */
@AllArgsConstructor
@Getter
public enum MetadataSyncStatus {
    PENDING("PENDING"),
    SYNCING("SYNCING"),
    READY("READY"),
    WAITING("WAITING"),
    ERROR("ERROR"),
    DELETING("DELETING");

    @EnumValue
    private final String code;
}
