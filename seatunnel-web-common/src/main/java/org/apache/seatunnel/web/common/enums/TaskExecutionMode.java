package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Execution strategy for a batch task.
 *
 * <p>MANUAL tasks are submitted only by the executor endpoint. AUTO tasks are
 * submitted by Quartz after their definition is online.</p>
 */
@Getter
@AllArgsConstructor
public enum TaskExecutionMode {
    MANUAL("MANUAL", "手动执行"),
    AUTO("AUTO", "自动调度");

    @EnumValue
    private final String code;
    private final String description;

    /**
     * Resolve old clients that did not send executionMode.
     */
    public static TaskExecutionMode resolve(TaskExecutionMode requested, String cronExpression) {
        if (requested != null) {
            return requested;
        }
        return cronExpression == null || cronExpression.trim().isEmpty() ? MANUAL : AUTO;
    }

    public boolean isManual() {
        return this == MANUAL;
    }

    public boolean isAuto() {
        return this == AUTO;
    }
}
