package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum LakeOperationType {
    CREATE_DATABASE("CREATE_DATABASE"),
    DROP_DATABASE("DROP_DATABASE"),
    CREATE_TABLE("CREATE_TABLE"),
    DROP_TABLE("DROP_TABLE"),
    CREATE_CATALOG("CREATE_CATALOG"),
    UPDATE_CATALOG("UPDATE_CATALOG"),
    REFRESH_CATALOG("REFRESH_CATALOG"),
    DROP_CATALOG("DROP_CATALOG"),
    READONLY_QUERY("READONLY_QUERY"),
    ALTER_RETENTION("ALTER_RETENTION"),
    RECONCILE("RECONCILE"),
    RETRY("RETRY"),
    VALIDATE("VALIDATE");

    @EnumValue
    private final String code;
}
