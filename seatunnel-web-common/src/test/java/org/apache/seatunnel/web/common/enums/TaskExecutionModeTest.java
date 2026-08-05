package org.apache.seatunnel.web.common.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskExecutionModeTest {

    @Test
    void infersManualWhenLegacyScheduleHasNoCron() {
        assertEquals(TaskExecutionMode.MANUAL, TaskExecutionMode.resolve(null, null));
        assertEquals(TaskExecutionMode.MANUAL, TaskExecutionMode.resolve(null, "  "));
    }

    @Test
    void infersAutoWhenLegacyScheduleHasCron() {
        assertEquals(
                TaskExecutionMode.AUTO,
                TaskExecutionMode.resolve(null, "0 0 2 * * ?")
        );
    }

    @Test
    void explicitModeWinsOverLegacyCronValue() {
        assertEquals(
                TaskExecutionMode.MANUAL,
                TaskExecutionMode.resolve(TaskExecutionMode.MANUAL, "0 0 2 * * ?")
        );
        assertEquals(
                TaskExecutionMode.AUTO,
                TaskExecutionMode.resolve(TaskExecutionMode.AUTO, null)
        );
    }
}
