package org.apache.seatunnel.web.api.log;

import org.apache.seatunnel.web.common.enums.JobMode;

import java.nio.file.Path;

/** Resolves configured log directories without prefixing an already absolute volume path. */
public final class JobLogPathResolver {

    private JobLogPathResolver() {
    }

    public static String buildLogPath(String configuredDirectory, Long instanceId, JobMode jobMode) {
        String configured = configuredDirectory == null || configuredDirectory.isBlank()
                ? "logs"
                : configuredDirectory.trim();
        Path directory = Path.of(configured);
        if (!directory.isAbsolute()) {
            directory = Path.of(System.getProperty("user.dir")).resolve(directory);
        }
        String prefix = jobMode == JobMode.STREAMING ? "streaming-job-" : "job-";
        return directory.resolve(prefix + instanceId + ".log").toAbsolutePath().normalize().toString();
    }

    public static Path resolveStoredPath(String storedPath) {
        Path path = Path.of(storedPath);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
}
