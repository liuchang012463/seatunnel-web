package org.apache.seatunnel.web.api.log;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobLogParserTest {

    private final JobLogParser parser = new JobLogParser();

    @Test
    void classifiesWebAndEngineLinesWithoutDiscardingOriginalText() {
        String content = """
                [2026-08-05 10:00:00] [INFO] Submitting batch job via REST API
                [2026-08-05 10:00:01] [ERROR] source timeout after 30 seconds
                === SEA TUNNEL ENGINE LOG SNAPSHOT sha256=test ===
                {\"level\":\"ERROR\",\"message\":\"sink connection refused\"}
                === END SEA TUNNEL ENGINE LOG SNAPSHOT ===
                """;

        List<JobLogEntry> entries = parser.parse(content);

        assertEquals(3, entries.size());
        assertEquals("OPERATION", entries.get(0).category());
        assertEquals("ERROR", entries.get(1).category());
        assertEquals("ENGINE", entries.get(2).source());
        assertEquals("ERROR", entries.get(2).eventType());
        assertTrue(entries.get(2).message().contains("connection refused"));
        assertEquals(1000L, entries.get(1).elapsedMs());

        JobLogStructuredRecord structured = parser.toStructuredRecord(entries.get(0));
        assertEquals("提交任务", structured.operation());
        assertEquals("进行中", structured.status());
    }
}
