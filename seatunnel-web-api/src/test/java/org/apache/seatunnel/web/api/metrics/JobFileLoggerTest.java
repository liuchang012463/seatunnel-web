package org.apache.seatunnel.web.api.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JobFileLoggerTest {

    @TempDir
    Path tempDir;

    @Test
    void writesEveryMessageWhenLogVolumeExceedsTheFormerQueueCapacity() throws Exception {
        Path logPath = tempDir.resolve("job.log");
        JobFileLogger logger = new JobFileLogger(logPath.toString());

        for (int index = 0; index < 12_000; index++) {
            logger.info("message-" + index);
        }
        logger.error("failure", new IllegalStateException("boom"));
        logger.close();

        String content = Files.readString(logPath, StandardCharsets.UTF_8);
        assertTrue(content.contains("message-0"));
        assertTrue(content.contains("message-11999"));
        assertTrue(content.contains("failure"));
        assertTrue(content.contains("IllegalStateException: boom"));
    }
}
