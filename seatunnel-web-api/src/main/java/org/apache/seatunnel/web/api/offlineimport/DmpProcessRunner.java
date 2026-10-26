package org.apache.seatunnel.web.api.offlineimport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Runs one external restore tool, streaming its output into the bounded log buffer. */
public class DmpProcessRunner {

    private static final int OUTPUT_CHUNK_CHARS = 4096;
    private static final int MAX_OUTPUT_LINE_CHARS = 8192;
    private static final long TERMINATION_GRACE_MILLIS = 1000;
    private static final long FORCE_TERMINATION_WAIT_MILLIS = 5000;

    /** Exit information of the finished process. */
    public record ProcessResult(int exitCode, boolean timedOut) {
        public boolean success() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * @throws IOException when the executable is missing or cannot start; the
     *                     caller turns that into a generic task failure.
     */
    public ProcessResult run(
            DmpCommandBuilder.DmpCommand dmpCommand,
            Map<String, String> extraEnvironment,
            Path workDir,
            long timeoutMinutes,
            ExecutionLogBuffer log) throws IOException, InterruptedException {
        return run(dmpCommand, extraEnvironment, workDir, timeoutMinutes, log, null, StandardCharsets.UTF_8);
    }

    /**
     * Runs a process and optionally observes each bounded output line. The
     * observer is used by archive preflight to detect schema declarations
     * without retaining a potentially large TOC in memory.
     */
    public ProcessResult run(
            DmpCommandBuilder.DmpCommand dmpCommand,
            Map<String, String> extraEnvironment,
            Path workDir,
            long timeoutMinutes,
            ExecutionLogBuffer log,
            Consumer<String> outputObserver) throws IOException, InterruptedException {
        return run(dmpCommand, extraEnvironment, workDir, timeoutMinutes, log, outputObserver,
                StandardCharsets.UTF_8);
    }

    /** Same bounded stream handling with the native tool's documented output charset. */
    public ProcessResult run(
            DmpCommandBuilder.DmpCommand dmpCommand,
            Map<String, String> extraEnvironment,
            Path workDir,
            long timeoutMinutes,
            ExecutionLogBuffer log,
            Consumer<String> outputObserver,
            Charset outputCharset) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(dmpCommand.command());
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        if (extraEnvironment != null) {
            builder.environment().putAll(extraEnvironment);
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            log.line("无法启动还原工具（" + e.getClass().getSimpleName() + "）");
            throw e;
        }
        // None of the native restore/preflight commands consume interactive
        // input. Closing stdin immediately prevents a prompt from leaving a
        // worker blocked indefinitely while stdout is still being drained.
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            terminateAndWait(process, log);
            log.line("无法关闭还原工具标准输入（" + e.getClass().getSimpleName() + "）");
            throw e;
        }

        AtomicReference<RuntimeException> observerFailure = new AtomicReference<>();
        Thread outputDrain = new Thread(() -> drainOutput(
                process.getInputStream(), outputCharset, log, outputObserver, observerFailure),
                "offline-import-dmp-output");
        outputDrain.setDaemon(true);
        outputDrain.start();

        boolean finished;
        try {
            finished = process.waitFor(Math.max(0, timeoutMinutes), TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            terminateAndWait(process, log);
            closeAndJoinOutput(process, outputDrain);
            log.line("执行线程被中断，已强制终止还原工具");
            Thread.currentThread().interrupt();
            throw interrupted;
        }

        if (!finished) {
            terminateAndWait(process, log);
            log.line("还原工具执行超时（" + timeoutMinutes + " 分钟），已强制终止");
        }
        closeAndJoinOutput(process, outputDrain);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("DMP tool shutdown interrupted");
        }

        RuntimeException observerException = observerFailure.get();
        if (observerException != null) {
            throw new IllegalStateException("读取还原工具输出失败");
        }
        return finished ? new ProcessResult(process.exitValue(), false) : new ProcessResult(-1, true);
    }

    private void drainOutput(
            InputStream input,
            Charset charset,
            ExecutionLogBuffer log,
            Consumer<String> outputObserver,
            AtomicReference<RuntimeException> observerFailure) {
        try (InputStreamReader reader = new InputStreamReader(input, charset)) {
            char[] chunk = new char[OUTPUT_CHUNK_CHARS];
            StringBuilder line = new StringBuilder(MAX_OUTPUT_LINE_CHARS);
            boolean truncated = false;
            int read;
            while ((read = reader.read(chunk)) >= 0) {
                for (int index = 0; index < read; index++) {
                    char current = chunk[index];
                    if (current == '\n') {
                        emitLine(line, truncated, log, outputObserver, observerFailure);
                        line.setLength(0);
                        truncated = false;
                    } else if (line.length() < MAX_OUTPUT_LINE_CHARS) {
                        line.append(current);
                    } else {
                        // Continue draining the stream without growing memory
                        // for an arbitrarily long line with no newline.
                        truncated = true;
                    }
                }
            }
            if (line.length() > 0 || truncated) {
                emitLine(line, truncated, log, outputObserver, observerFailure);
            }
        } catch (IOException ignored) {
            // Terminating the native tool closes the pipe; its exit result is
            // the authoritative outcome.
        }
    }

    private void emitLine(
            StringBuilder line,
            boolean truncated,
            ExecutionLogBuffer log,
            Consumer<String> outputObserver,
            AtomicReference<RuntimeException> observerFailure) {
        int length = line.length();
        if (length > 0 && line.charAt(length - 1) == '\r') {
            length--;
        }
        String value = line.substring(0, length) + (truncated ? " …（超长行已截断）" : "");
        log.raw(value);
        if (outputObserver != null && observerFailure.get() == null) {
            try {
                outputObserver.accept(value);
            } catch (RuntimeException e) {
                // Keep draining to prevent the child from blocking on a full
                // pipe, then report a generic output-processing failure.
                observerFailure.compareAndSet(null, e);
            }
        }
    }

    private void terminateAndWait(Process process, ExecutionLogBuffer log) {
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        boolean interrupted = waitForTree(process, descendants, TERMINATION_GRACE_MILLIS);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        interrupted |= waitForTree(process, descendants, FORCE_TERMINATION_WAIT_MILLIS);
        if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            // SIGKILL has been sent to the entire captured tree. Do not let a
            // misbehaving child keep the worker blocked forever while waiting
            // for an exit notification that the operating system may delay.
            log.line("强制终止后仍有还原子进程存活");
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean waitForTree(Process process, java.util.List<ProcessHandle> descendants, long timeoutMillis) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private void closeAndJoinOutput(Process process, Thread outputDrain) {
        joinUninterruptibly(outputDrain, 5000);
        if (outputDrain.isAlive()) {
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // The process has already stopped; closing only unblocks drain.
            }
            joinUninterruptibly(outputDrain, 1000);
        }
    }

    private void joinUninterruptibly(Thread thread, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean interrupted = false;
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
