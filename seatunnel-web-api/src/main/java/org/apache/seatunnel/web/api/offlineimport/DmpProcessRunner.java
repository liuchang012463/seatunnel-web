package org.apache.seatunnel.web.api.offlineimport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs one external restore tool, streaming its output into the log buffer. */
public class DmpProcessRunner {

    /** Exit information of the finished process. */
    public record ProcessResult(int exitCode, boolean timedOut) {
        public boolean success() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * @throws IOException when the executable is missing or cannot start; the
     *                     caller turns that into the task failure message.
     */
    public ProcessResult run(
            DmpCommandBuilder.DmpCommand dmpCommand,
            Map<String, String> extraEnvironment,
            Path workDir,
            long timeoutMinutes,
            ExecutionLogBuffer log) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(dmpCommand.command());
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        if (extraEnvironment != null) {
            builder.environment().putAll(extraEnvironment);
        }

        Process process;
        try {
            process = builder.start();
            // A tool that unexpectedly asks for input must fail instead of
            // blocking until the process timeout.
            process.getOutputStream().close();
        } catch (IOException e) {
            log.line("无法启动还原工具: " + e.getMessage());
            throw e;
        }

        boolean finished;
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            Thread outputDrain = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.raw(line);
                    }
                } catch (IOException ignored) {
                    // Process death closes the stream; the exit code decides the outcome.
                }
            }, "offline-import-dmp-output");
            outputDrain.setDaemon(true);
            outputDrain.start();
            try {
                finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                // Worker shutdown: leaving the tool running would keep writing
                // to the target database after the task is marked FAILED.
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                log.line("执行线程被中断，已强制终止还原工具");
                throw e;
            }
            if (!finished) {
                process.destroyForcibly();
                log.line("还原工具执行超时（" + timeoutMinutes + " 分钟），已强制终止");
            }
            outputDrain.join(5000);
        }

        if (!finished) {
            process.waitFor(10, TimeUnit.SECONDS);
            return new ProcessResult(-1, true);
        }
        return new ProcessResult(process.waitFor(), false);
    }
}
