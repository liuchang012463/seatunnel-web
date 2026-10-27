package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionLogBufferTest {

    @Test
    void keepsTheLastLinesOfAMultiLineBlock() {
        ExecutionLogBuffer buffer = new ExecutionLogBuffer(64 * 1024);
        buffer.line("工具日志尾部:");
        for (int i = 0; i < 200; i++) {
            buffer.raw("tool-line-" + i);
        }
        buffer.raw("FINAL ERROR: permission denied");

        String snapshot = buffer.snapshot();
        assertTrue(snapshot.contains("tool-line-199"));
        assertTrue(snapshot.contains("FINAL ERROR: permission denied"));
    }
}
