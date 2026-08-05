package org.apache.seatunnel.web.api.metrics;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class JobFileLogger {

    private final String logFilePath;

    private static final ConcurrentHashMap<String, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    /**
     * Thread-safe time formatter
     */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReentrantLock fileLock;
    private BufferedWriter writer;
    private volatile boolean closed;

    public JobFileLogger(String logFilePath) {
        this.logFilePath = logFilePath;
        this.fileLock = lockFor(logFilePath);
        init();
    }

    private void init() {
        if (logFilePath == null || logFilePath.isBlank()) {
            log.error("Failed to initialize JobFileLogger because log path is blank");
            return;
        }

        fileLock.lock();
        try {
            Path path = Paths.get(logFilePath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.error("Failed to initialize JobFileLogger: {}", logFilePath, e);
        } finally {
            fileLock.unlock();
        }
    }

    public void info(String message) {
        offer("INFO", message);
    }

    public void warn(String message) {
        offer("WARN", message);
    }

    public void error(String message) {
        offer("ERROR", message);
    }

    public void error(String message, Throwable t) {
        offer("ERROR", message);
        if (t != null) {
            offer("ERROR", stackTraceToString(t));
        }
    }

    private void offer(String level, String message) {
        String time = FORMATTER.format(LocalDateTime.now());
        String formatted = "[" + time + "] [" + level + "] " + message;

        fileLock.lock();
        try {
            if (writer == null || closed) {
                appendExternal(logFilePath, formatted);
                return;
            }

            writer.write(formatted);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to append job log: {}", logFilePath, e);
        } finally {
            fileLock.unlock();
        }
    }

    private String stackTraceToString(Throwable t) {
        StringWriter stringWriter = new StringWriter();
        t.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    public void flush() {
        fileLock.lock();
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (IOException e) {
            log.error("Failed to flush job log: {}", logFilePath, e);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Close this logger after flushing every accepted message.  The previous
     * implementation used a bounded non-blocking queue and could silently
     * discard messages when the queue was full; writes are now serialized and
     * flushed before returning.
     */
    public void close() {
        fileLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException e) {
            log.error("Failed to close job log: {}", logFilePath, e);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Appends a complete externally fetched section, such as a SeaTunnel
     * Engine log snapshot, using the same per-file lock as normal task logs.
     */
    public static void appendExternal(String logFilePath, String content) {
        if (logFilePath == null || logFilePath.isBlank() || content == null || content.isEmpty()) {
            return;
        }

        ReentrantLock lock = lockFor(logFilePath);
        lock.lock();
        try {
            Path path = Paths.get(logFilePath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (BufferedWriter externalWriter = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            )) {
                externalWriter.write(content);
                if (!content.endsWith(System.lineSeparator())) {
                    externalWriter.newLine();
                }
                externalWriter.flush();
            }
        } catch (IOException e) {
            log.error("Failed to append external job log: {}", logFilePath, e);
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock lockFor(String logFilePath) {
        String key = logFilePath == null ? "<blank>" : Paths.get(logFilePath).toAbsolutePath().normalize().toString();
        return FILE_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
    }
}
