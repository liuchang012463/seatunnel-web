package org.apache.seatunnel.web.api.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobLogFileReaderTest {

    @TempDir
    Path tempDir;

    private final JobLogFileReader reader = new JobLogFileReader();

    @Test
    void keepsTailAndHistoryCursorPagesOnUtf8CharacterBoundaries() throws Exception {
        String log = "界".repeat(100_000);
        Path path = tempDir.resolve("long-utf8.log");
        Files.writeString(path, log, StandardCharsets.UTF_8);

        JobLogFileReader.Page tail = reader.read(path, null, null);

        assertTrue(tail.hasOlder());
        assertFalse(tail.content().contains("\uFFFD"));
        assertTrue(tail.endCursor() - tail.startCursor() <= JobLogFileReader.MAX_PAGE_BYTES);
        assertEquals(tail.endCursor() - tail.startCursor(),
                tail.content().getBytes(StandardCharsets.UTF_8).length);

        JobLogFileReader.Page older = reader.read(path, null, tail.startCursor());
        assertEquals(tail.startCursor(), older.endCursor());
        assertTrue(older.endCursor() - older.startCursor() <= JobLogFileReader.MAX_PAGE_BYTES);
        assertFalse(older.content().contains("\uFFFD"));
        assertEquals(older.endCursor() - older.startCursor(),
                older.content().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void alignsCallerSuppliedCursorThatFallsInsideUtf8Character() throws Exception {
        Path path = tempDir.resolve("cursor.log");
        Files.writeString(path, "界日志", StandardCharsets.UTF_8);

        JobLogFileReader.Page page = reader.read(path, 1L, null);

        assertEquals(3L, page.startCursor());
        assertEquals("日志", page.content());
    }
}
