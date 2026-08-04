package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Business lifecycle status of a data source.
 *
 * <p>This is intentionally separate from {@link ConnStatus}, which describes
 * the result of the most recent connectivity test.</p>
 */
@AllArgsConstructor
@Getter
public enum DataSourceLifecycleStatus {
    ENABLED("ENABLED", "已启用"),
    DISABLED("DISABLED", "已停用"),
    REVOKED("REVOKED", "已注销");

    @EnumValue
    private final String code;
    private final String description;
}
