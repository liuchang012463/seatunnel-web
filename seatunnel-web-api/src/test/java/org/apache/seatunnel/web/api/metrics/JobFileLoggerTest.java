package org.apache.seatunnel.web.api.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * The log file is served to the browser and shipped to the log collectors, so the
     * credentials of the Engine must not reach it.
     */
    @Test
    void masksTheCredentialsOfTheEngineText() throws Exception {
        Path logPath = tempDir.resolve("masked.log");
        JobFileLogger logger = new JobFileLogger(logPath.toString());

        logger.error("submit failed: password=engine-password");
        logger.error("submit failed", new IllegalStateException("password=cause-password"));
        logger.close();

        String content = Files.readString(logPath, StandardCharsets.UTF_8);
        assertTrue(content.contains("submit failed"));
        assertFalse(content.contains("engine-password"));
        assertFalse(content.contains("cause-password"));
        assertTrue(content.contains("******"));
    }
}
