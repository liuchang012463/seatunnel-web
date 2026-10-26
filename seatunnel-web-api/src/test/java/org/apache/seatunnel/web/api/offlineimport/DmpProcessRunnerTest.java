package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmpProcessRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void drainsVeryLongLineWithBoundedMemoryAndContinuesAfterIt() throws Exception {
        String script = "i=0; while [ $i -lt 100000 ]; do printf x; i=$((i+1)); done; printf '\\nVISIBLE_MARKER\\n'";
        ExecutionLogBuffer log = new ExecutionLogBuffer(20_000);
        AtomicReference<String> markerLine = new AtomicReference<>();
        AtomicBoolean sawTruncatedLine = new AtomicBoolean();

        DmpProcessRunner.ProcessResult result = new DmpProcessRunner().run(
                command("/bin/sh", "-c", script), Map.of(), tempDir, 1, log,
                line -> {
                    if (line.contains("VISIBLE_MARKER")) {
                        markerLine.set(line);
                    }
                    if (line.contains("超长行已截断")) {
                        sawTruncatedLine.set(true);
                    }
                });

        assertTrue(result.success());
        assertNotNull(markerLine.get());
        assertTrue(markerLine.get().length() < 9_000);
        assertTrue(sawTruncatedLine.get());
        assertTrue(log.snapshot().length() < 20_000);
        assertTrue(log.snapshot().contains("...(截断)"));
    }

    @Test
    void closesStdinSoInteractiveToolCannotWaitForInput() throws Exception {
        ExecutionLogBuffer log = new ExecutionLogBuffer(2_000);
        DmpProcessRunner.ProcessResult result = new DmpProcessRunner().run(
                command("/bin/sh", "-c", "if IFS= read -r value; then printf 'UNEXPECTED_INPUT\\n'; "
                        + "else printf 'STDIN_CLOSED\\n'; fi"),
                Map.of(), tempDir, 1, log);

        assertTrue(result.success());
        assertTrue(log.snapshot().contains("STDIN_CLOSED"));
        assertFalse(log.snapshot().contains("UNEXPECTED_INPUT"));
    }

    @Test
    void redactsNativeToolOutputBeforePersistingIt() throws Exception {
        ExecutionLogBuffer log = new ExecutionLogBuffer(2_000);
        for (String secret : DmpArchivePreflight.secretVariants("Pwd @#")) {
            log.addSecret(secret);
        }

        DmpProcessRunner.ProcessResult result = new DmpProcessRunner().run(
                command("/bin/sh", "-c", "printf '%s\\n' 'Pwd @#' 'Pwd+%40%23'"),
                Map.of(), tempDir, 1, log);

        assertTrue(result.success());
        assertFalse(log.snapshot().contains("Pwd @#"));
        assertFalse(log.snapshot().contains("Pwd+%40%23"));
        assertTrue(log.snapshot().contains("[已脱敏]"));
    }

    @Test
    void decodesDamengSchemaListingWithTheNativeCharset() throws Exception {
        Path outputFile = tempDir.resolve("dameng-show.txt");
        Files.writeString(outputFile, "模式CODEX_DMP_SRC_274219 含有 1 个表", Charset.forName("GB18030"));
        AtomicBoolean schemaFound = new AtomicBoolean();

        DmpProcessRunner.ProcessResult result = new DmpProcessRunner().run(
                command("/bin/cat", outputFile.toString()), Map.of(), tempDir, 1,
                new ExecutionLogBuffer(2_000),
                line -> schemaFound.set(DmpArchivePreflight.damengSchemaEntry(line, "CODEX_DMP_SRC_274219")),
                Charset.forName("GB18030"));

        assertTrue(result.success());
        assertTrue(schemaFound.get());
    }

    @Test
    void interruptionKillsToolAndItsChildBeforeReturning() throws Exception {
        Path childPidFile = tempDir.resolve("child.pid");
        Path finishedFile = tempDir.resolve("finished");
        String script = "sleep 30 & child=$!; printf '%s\\n' \"$child\" > \"$1\"; "
                + "printf 'started\\n'; wait \"$child\"; printf done > \"$2\"";
        ExecutionLogBuffer log = new ExecutionLogBuffer(2_000);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<String> failureType = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                new DmpProcessRunner().run(
                        command("/bin/sh", "-c", script, "runner", childPidFile.toString(), finishedFile.toString()),
                        Map.of(), tempDir, 1, log);
            } catch (InterruptedException e) {
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Exception e) {
                failureType.set(e.getClass().getSimpleName());
            }
        });
        worker.setDaemon(true);
        worker.start();

        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!log.snapshot().contains("started") && worker.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(log.snapshot().contains("started"));
            assertTrue(Files.exists(childPidFile));
            long childPid = Long.parseLong(Files.readString(childPidFile).trim());

            worker.interrupt();
            worker.join(5_000);

            assertFalse(worker.isAlive());
            assertTrue(interrupted.get());
            assertTrue(failureType.get() == null);
            assertFalse(Files.exists(finishedFile));
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(5_000);
            }
        }
    }

    @Test
    void killsTermIgnoringChildEvenWhenItsParentExitsOnTerm() throws Exception {
        Path childPidFile = tempDir.resolve("term-ignoring-child.pid");
        Path writesFile = tempDir.resolve("term-ignoring-child-writes");
        String childScript = "trap \"\" TERM; while :; do printf x >> \"$1\"; sleep 0.05; done";
        String parentScript = "trap 'exit 0' TERM; sh -c '" + childScript + "' child \"$2\" & "
                + "child=$!; printf '%s\\n' \"$child\" > \"$1\"; printf 'parent-ready\\n'; wait \"$child\"";
        ExecutionLogBuffer log = new ExecutionLogBuffer(2_000);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<String> failureType = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                new DmpProcessRunner().run(
                        command("/bin/sh", "-c", parentScript, "runner",
                                childPidFile.toString(), writesFile.toString()),
                        Map.of(), tempDir, 1, log);
            } catch (InterruptedException e) {
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Exception e) {
                failureType.set(e.getClass().getSimpleName());
            }
        });
        worker.setDaemon(true);
        worker.start();

        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(childPidFile) && worker.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(childPidFile));
            long childPid = Long.parseLong(Files.readString(childPidFile).trim());
            worker.interrupt();
            worker.join(8_000);

            assertFalse(worker.isAlive());
            assertTrue(interrupted.get());
            assertTrue(failureType.get() == null);
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
            long writesAfterExit = Files.size(writesFile);
            Thread.sleep(100);
            assertTrue(Files.size(writesFile) <= writesAfterExit);
        } finally {
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(8_000);
            }
        }
    }

    private DmpCommandBuilder.DmpCommand command(String... args) {
        return new DmpCommandBuilder.DmpCommand(List.of(args), Map.of());
    }
}
