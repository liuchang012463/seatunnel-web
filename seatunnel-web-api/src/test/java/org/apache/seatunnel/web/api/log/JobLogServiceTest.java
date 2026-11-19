package org.apache.seatunnel.web.api.log;

import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobLogServiceTest {

    @Mock
    private JobInstanceDao jobInstanceDao;

    @Mock
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @Mock
    private SeaTunnelRestClient seaTunnelRestClient;

    @Spy
    private JobLogParser jobLogParser = new JobLogParser();

    @InjectMocks
    private JobLogService jobLogService;

    @TempDir
    Path tempDir;

    @Test
    void persistsEngineSnapshotOnceByContentHashAndReturnsCompleteDocument() throws Exception {
        Path logPath = tempDir.resolve("job-1.log");
        Files.writeString(logPath, "[2026-08-05 10:00:00] [INFO] submit\n", StandardCharsets.UTF_8);

        JobInstance instance = JobInstance.builder()
                .id(1L)
                .jobDefinitionId(10L)
                .clientId(2L)
                .engineJobId("engine-1")
                .jobStatus(JobStatus.FINISHED)
                .runtimeConfig("source { plugin = Jdbc }")
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(1L)).thenReturn(instance);
        SeaTunnelRestClient.NodeLogFile nodeLogFile = new SeaTunnelRestClient.NodeLogFile(
                "master:8080",
                "http://master:8080/logs/job-engine-1.log",
                "job-engine-1.log"
        );
        when(seaTunnelRestClient.jobLogFiles(2L, "engine-1")).thenReturn(List.of(nodeLogFile));
        CountDownLatch fetched = new CountDownLatch(1);
        when(seaTunnelRestClient.writeJobLogFile(eq(2L), eq("engine-1"), eq(nodeLogFile), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(3);
                    byte[] bytes = "engine failure: connection refused".getBytes(StandardCharsets.UTF_8);
                    output.write(bytes);
                    fetched.countDown();
                    return (long) bytes.length;
                });

        jobLogService.persistEngineLog(1L, JobMode.BATCH);
        jobLogService.persistEngineLog(1L, JobMode.BATCH);
        assertTrue(fetched.await(2, TimeUnit.SECONDS));
        awaitCollectionLockReleased(logPath);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertTrue(stored.contains("submit"));
        assertTrue(stored.contains("engine failure: connection refused"));
        assertEquals(1, count(stored, "=== SEA TUNNEL ENGINE LOG SNAPSHOT"));
        assertTrue(jobLogService.getFullContent(1L, JobMode.BATCH).contains("engine failure"));
        verify(seaTunnelRestClient, times(1)).jobLogFiles(eq(2L), eq("engine-1"));
        verify(seaTunnelRestClient, times(1))
                .writeJobLogFile(eq(2L), eq("engine-1"), eq(nodeLogFile), any(OutputStream.class));
    }

    @Test
    void appendsOnlyNewNodeBytesAndPacesActiveCollectionButRunsTerminalCollection() throws Exception {
        Path logPath = tempDir.resolve("job-7.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(7L)
                .clientId(2L)
                .engineJobId("engine-7")
                .jobStatus(JobStatus.RUNNING)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(7L)).thenReturn(instance);
        SeaTunnelRestClient.NodeLogFile nodeLogFile = new SeaTunnelRestClient.NodeLogFile(
                "master:8080",
                "http://master:8080/logs/job-engine-7.log",
                "job-engine-7.log"
        );
        when(seaTunnelRestClient.jobLogFiles(2L, "engine-7")).thenReturn(List.of(nodeLogFile));
        AtomicReference<String> engineLog = new AtomicReference<>("first line\n");
        CountDownLatch firstFetchCompleted = new CountDownLatch(1);
        when(seaTunnelRestClient.writeJobLogFile(eq(2L), eq("engine-7"), eq(nodeLogFile), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = engineLog.get().getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(3)).write(bytes);
                    firstFetchCompleted.countDown();
                    return (long) bytes.length;
                });

        jobLogService.persistEngineLog(7L, JobMode.BATCH);
        jobLogService.persistEngineLog(7L, JobMode.BATCH);
        assertTrue(firstFetchCompleted.await(2, TimeUnit.SECONDS));
        awaitCollectionLockReleased(logPath);
        verify(seaTunnelRestClient, times(1)).jobLogFiles(2L, "engine-7");

        instance.setJobStatus(JobStatus.FINISHED);
        engineLog.set("first line\nsecond line\n");
        jobLogService.persistFinalEngineLog(7L, JobMode.BATCH);
        jobLogService.persistFinalEngineLog(7L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, stored.lines().filter(line -> line.contains("first line")).count());
        assertTrue(stored.contains("second line"));
        assertTrue(stored.contains("delta=true fromBytes="));
        verify(seaTunnelRestClient, times(3)).jobLogFiles(2L, "engine-7");
    }

    @Test
    void fallsBackToTheConfiguredMasterWhenAllNodeLogLinksAreUnreachable() throws Exception {
        Path logPath = tempDir.resolve("job-8.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(8L)
                .clientId(2L)
                .engineJobId("engine-8")
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(8L)).thenReturn(instance);
        SeaTunnelRestClient.NodeLogFile masterLogFile = new SeaTunnelRestClient.NodeLogFile(
                "master:8080", "http://master:8080/logs/job-engine-8.log", "job-engine-8.log");
        SeaTunnelRestClient.NodeLogFile workerLogFile = new SeaTunnelRestClient.NodeLogFile(
                "worker1:8080", "http://worker1:8080/logs/job-engine-8.log", "job-engine-8.log");
        when(seaTunnelRestClient.jobLogFiles(2L, "engine-8"))
                .thenReturn(List.of(masterLogFile, workerLogFile));
        when(seaTunnelRestClient.writeJobLogFile(
                eq(2L), eq("engine-8"), any(SeaTunnelRestClient.NodeLogFile.class), any(OutputStream.class)))
                .thenThrow(new IllegalStateException("Engine node is not reachable from Web"));
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-8"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = "shared volume worker output\n".getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    return (long) bytes.length;
                });

        jobLogService.persistFinalEngineLog(8L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertTrue(stored.contains("shared volume worker output"));
        assertTrue(stored.contains("node=__master_shared_logs__"));
        verify(seaTunnelRestClient, times(2))
                .writeJobLogFile(eq(2L), eq("engine-8"), any(SeaTunnelRestClient.NodeLogFile.class), any(OutputStream.class));
        verify(seaTunnelRestClient, times(1)).writeMasterJobLogFile(eq(2L), eq("engine-8"), any(OutputStream.class));
    }

    @Test
    void retriesNodeDirectoryAfterPartialTerminalPollAndDoesNotDuplicateSharedFallback() throws Exception {
        Path logPath = tempDir.resolve("job-9.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(9L)
                .clientId(2L)
                .engineJobId("engine-9")
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(9L)).thenReturn(instance);

        SeaTunnelRestClient.NodeLogFile masterLogFile = new SeaTunnelRestClient.NodeLogFile(
                "master:8080", "http://master:8080/logs/job-engine-9.log", "job-engine-9.log");
        SeaTunnelRestClient.NodeLogFile workerLogFile = new SeaTunnelRestClient.NodeLogFile(
                "worker1:8080", "http://worker1:8080/logs/job-engine-9.log", "job-engine-9.log");
        when(seaTunnelRestClient.jobLogFiles(2L, "engine-9"))
                .thenReturn(List.of(masterLogFile, workerLogFile));
        CountDownLatch firstWorkerAttempt = new CountDownLatch(1);
        AtomicInteger workerAttempts = new AtomicInteger();
        when(seaTunnelRestClient.writeJobLogFile(eq(2L), eq("engine-9"), any(), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    SeaTunnelRestClient.NodeLogFile file = invocation.getArgument(2);
                    if (file.equals(workerLogFile) && workerAttempts.incrementAndGet() == 1) {
                        firstWorkerAttempt.countDown();
                        throw new IllegalStateException("worker log unavailable");
                    }
                    byte[] bytes = "shared volume worker output\n".getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(3)).write(bytes);
                    return (long) bytes.length;
                });
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-9"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = "shared volume worker output\n".getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    return (long) bytes.length;
                });

        jobLogService.persistEngineLog(9L, JobMode.BATCH);
        assertTrue(firstWorkerAttempt.await(2, TimeUnit.SECONDS));
        awaitCollectionLockReleased(logPath);
        String partial = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, count(partial, "shared volume worker output"));
        assertTrue(Files.readString(logPath.resolveSibling("job-9.log.engine-state"), StandardCharsets.UTF_8)
                .contains("terminalCollected=false"));

        jobLogService.persistFinalEngineLog(9L, JobMode.BATCH);

        String recovered = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, count(recovered, "shared volume worker output"));
        assertTrue(Files.readString(logPath.resolveSibling("job-9.log.engine-state"), StandardCharsets.UTF_8)
                .contains("terminalCollected=true"));
        verify(seaTunnelRestClient, times(2)).jobLogFiles(2L, "engine-9");
        verify(seaTunnelRestClient, times(2)).writeJobLogFile(
                eq(2L), eq("engine-9"), eq(workerLogFile), any(OutputStream.class));
        verify(seaTunnelRestClient, times(1)).writeMasterJobLogFile(
                eq(2L), eq("engine-9"), any(OutputStream.class));
    }

    @Test
    void returnsTailAndSearchBeforeAMergedSlowEngineRefreshCompletes() throws Exception {
        Path logPath = tempDir.resolve("job-10.log");
        JobInstance instance = JobInstance.builder()
                .id(10L)
                .clientId(2L)
                .engineJobId("engine-10")
                .jobStatus(JobStatus.RUNNING)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(10L)).thenReturn(instance);

        CountDownLatch remoteReadStarted = new CountDownLatch(1);
        CountDownLatch releaseRemoteRead = new CountDownLatch(1);
        when(seaTunnelRestClient.jobLogFiles(2L, "engine-10")).thenAnswer(invocation -> {
            remoteReadStarted.countDown();
            if (!releaseRemoteRead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the Engine fixture");
            }
            return List.of();
        });

        FutureTask<JobLogTailResult> tailRequest = new FutureTask<>(
                () -> jobLogService.tail(10L, JobMode.BATCH, null, null));
        Thread requestThread = new Thread(tailRequest, "job-log-tail-test");
        requestThread.start();
        try {
            assertTrue(remoteReadStarted.await(2, TimeUnit.SECONDS));
            JobLogTailResult tail = tailRequest.get(500, TimeUnit.MILLISECONDS);
            assertEquals(JobLogAvailability.PENDING, tail.availability());
            assertEquals("", tail.content());

            JobLogSearchResult search = jobLogService.search(
                    10L, JobMode.BATCH, "missing", null, null, null, 1, 10);
            assertEquals(0L, search.total());
            jobLogService.persistEngineLog(10L, JobMode.BATCH);
            verify(seaTunnelRestClient, times(1)).jobLogFiles(2L, "engine-10");
        } finally {
            releaseRemoteRead.countDown();
            requestThread.join(1_000L);
            awaitCollectionLockReleased(logPath);
        }
    }

    @Test
    void doesNotDuplicateACompletedAppendWhenCursorSidecarWasNotUpdated() throws Exception {
        Path logPath = tempDir.resolve("job-11.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(11L)
                .clientId(2L)
                .engineJobId("engine-11")
                .jobStatus(JobStatus.RUNNING)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(11L)).thenReturn(instance);

        SeaTunnelRestClient.NodeLogFile nodeLogFile = new SeaTunnelRestClient.NodeLogFile(
                "master:8080", "http://master:8080/logs/job-engine-11.log", "job-engine-11.log");
        when(seaTunnelRestClient.jobLogFiles(2L, "engine-11")).thenReturn(List.of(nodeLogFile));
        AtomicReference<String> engineLog = new AtomicReference<>("first line\n");
        when(seaTunnelRestClient.writeJobLogFile(eq(2L), eq("engine-11"), eq(nodeLogFile), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = engineLog.get().getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(3)).write(bytes);
                    return (long) bytes.length;
                });

        jobLogService.persistFinalEngineLog(11L, JobMode.BATCH);
        engineLog.set("first line\nsecond line\n");
        String updatedSnapshot = engineLog.get();
        String hash = sha256(updatedSnapshot);
        String completedFrame = System.lineSeparator()
                + "=== SEA TUNNEL ENGINE LOG SNAPSHOT sha256=" + hash
                + " node=master:8080 delta=true fromBytes=11 engineJobId=engine-11 ==="
                + System.lineSeparator()
                + "second line\n"
                + System.lineSeparator()
                + "=== END SEA TUNNEL ENGINE LOG SNAPSHOT ==="
                + System.lineSeparator();
        Files.writeString(logPath, completedFrame, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        long lengthAfterInterruptedAppend = Files.size(logPath);

        jobLogService.persistFinalEngineLog(11L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(lengthAfterInterruptedAppend, Files.size(logPath));
        assertEquals(1, count(stored, "second line"));
        assertEquals(1, count(stored, "sha256=" + hash));
        assertTrue(Files.exists(logPath.resolveSibling("job-11.log.engine-state.lock")));
    }

    @Test
    void truncatesAnIncompleteEngineFrameBeforeAppendingTheUpdatedSnapshot() throws Exception {
        Path logPath = tempDir.resolve("job-12.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(12L)
                .clientId(2L)
                .engineJobId("engine-12")
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(12L)).thenReturn(instance);

        SeaTunnelRestClient.NodeLogFile nodeLogFile = new SeaTunnelRestClient.NodeLogFile(
                "master:8080", "http://master:8080/logs/job-engine-12.log", "job-engine-12.log");
        when(seaTunnelRestClient.jobLogFiles(2L, "engine-12")).thenReturn(List.of(nodeLogFile));
        AtomicReference<String> engineLog = new AtomicReference<>("first line\n");
        when(seaTunnelRestClient.writeJobLogFile(eq(2L), eq("engine-12"), eq(nodeLogFile), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = engineLog.get().getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(3)).write(bytes);
                    return (long) bytes.length;
                });

        jobLogService.persistFinalEngineLog(12L, JobMode.BATCH);
        Files.writeString(logPath, "web event after Engine output\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        engineLog.set("first line\nsecond line\n");
        String hash = sha256(engineLog.get());
        String incompleteFrame = System.lineSeparator()
                + "=== SEA TUNNEL ENGINE LOG SNAPSHOT sha256=" + hash
                + " node=master:8080 delta=true fromBytes=11 engineJobId=engine-12 ==="
                + System.lineSeparator()
                + "second line\n";
        Files.writeString(logPath, incompleteFrame, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        jobLogService.persistFinalEngineLog(12L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertTrue(stored.startsWith("web start\n"));
        assertTrue(stored.contains("web event after Engine output"));
        assertEquals(1, count(stored, "first line"));
        assertEquals(1, count(stored, "second line"));
        assertEquals(1, count(stored, "sha256=" + hash));
        assertEquals(2, count(stored, "=== END SEA TUNNEL ENGINE LOG SNAPSHOT ==="));
        verify(seaTunnelRestClient, times(2)).jobLogFiles(2L, "engine-12");
    }

    @Test
    void analyzesTheBoundedLocalTailWhileEngineRefreshRunsInBackground() throws Exception {
        Path logPath = tempDir.resolve("job-13.log");
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < JobLogFileReader.MAX_PAGE_LINES + 1; index++) {
            content.append("[2026-08-05 10:00:00] [INFO] submit started");
            if (index < JobLogFileReader.MAX_PAGE_LINES) {
                content.append(System.lineSeparator());
            }
        }
        Files.writeString(logPath, content, StandardCharsets.UTF_8);

        JobInstance instance = JobInstance.builder()
                .id(13L)
                .clientId(2L)
                .engineJobId("engine-13")
                .jobStatus(JobStatus.RUNNING)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(13L)).thenReturn(instance);

        CountDownLatch remoteReadStarted = new CountDownLatch(1);
        CountDownLatch releaseRemoteRead = new CountDownLatch(1);
        when(seaTunnelRestClient.jobLogFiles(2L, "engine-13")).thenAnswer(invocation -> {
            remoteReadStarted.countDown();
            if (!releaseRemoteRead.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the Engine fixture");
            }
            return List.of();
        });

        FutureTask<JobLogAnalysisResult> analysisTask = new FutureTask<>(
                () -> jobLogService.analyze(13L, JobMode.BATCH));
        Thread requestThread = new Thread(analysisTask, "job-log-analysis-test");
        requestThread.start();
        try {
            assertTrue(remoteReadStarted.await(2, TimeUnit.SECONDS));
            JobLogAnalysisResult analysis = analysisTask.get(500, TimeUnit.MILLISECONDS);
            assertEquals(JobLogFileReader.MAX_PAGE_LINES, analysis.totalLines());
        } finally {
            releaseRemoteRead.countDown();
            requestThread.join(1_000L);
            awaitCollectionLockReleased(logPath);
        }
    }

    @Test
    void searchesCompleteDocumentByKeywordAndLevel() throws Exception {
        Path logPath = tempDir.resolve("job-2.log");
        Files.writeString(logPath, """
                [2026-08-05 10:00:00] [INFO] submit started
                [2026-08-05 10:00:01] [ERROR] timeout while reading source
                [2026-08-05 10:00:02] [WARN] retry scheduled
                """, StandardCharsets.UTF_8);

        JobInstance instance = JobInstance.builder()
                .id(2L)
                .clientId(2L)
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(2L)).thenReturn(instance);

        JobLogSearchResult result = jobLogService.search(
                2L,
                JobMode.BATCH,
                "timeout",
                "ERROR",
                null,
                null,
                1,
                10
        );

        assertEquals(1, result.total());
        assertEquals(1, result.entries().size());
        assertTrue(result.entries().get(0).message().contains("timeout"));
    }

    @Test
    void tailsAndStreamsThePersistedDocument() throws Exception {
        Path logPath = tempDir.resolve("job-6.log");
        String content = "[2026-08-05 10:00:00] [INFO] first line\n"
                + "[2026-08-05 10:00:01] [ERROR] second line\n";
        Files.writeString(logPath, content, StandardCharsets.UTF_8);

        JobInstance instance = JobInstance.builder()
                .id(6L)
                .clientId(2L)
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(6L)).thenReturn(instance);

        JobLogTailResult tail = jobLogService.tail(6L, JobMode.BATCH, null, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        jobLogService.writeFullContent(6L, JobMode.BATCH, output);

        assertEquals(JobLogAvailability.RETAINED, tail.availability());
        assertEquals(content, tail.content());
        assertEquals(content, output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void analyzesTheSameCompleteDocument() throws Exception {
        Path logPath = tempDir.resolve("job-3.log");
        Files.writeString(logPath, """
                [2026-08-05 10:00:00] [INFO] submit started
                [2026-08-05 10:00:01] [INFO] rows=10 bytes=2048
                [2026-08-05 10:00:02] [INFO] running source task
                [2026-08-05 10:00:03] [ERROR] connection refused
                """, StandardCharsets.UTF_8);

        JobInstance instance = JobInstance.builder()
                .id(3L)
                .clientId(2L)
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(3L)).thenReturn(instance);

        JobLogAnalysisResult analysis = jobLogService.analyze(3L, JobMode.BATCH);

        assertEquals(4, analysis.totalLines());
        assertEquals(1, analysis.operationRecords().size());
        assertEquals(1, analysis.dataSnapshots().size());
        assertEquals(1, analysis.executionFlow().size());
        assertEquals(1, analysis.errors().size());
        assertEquals("提交任务", analysis.operationRecords().get(0).operation());
        assertEquals("记录数据快照", analysis.dataSnapshots().get(0).operation());
        assertEquals("推进执行", analysis.executionFlow().get(0).operation());
    }

    @Test
    void groupsReplayIntoNamedSectionsAndKeepsNormalizedSteps() throws Exception {
        Path logPath = tempDir.resolve("job-4.log");
        Files.writeString(logPath, """
                [2026-08-05 10:00:00] [INFO] submit started
                [2026-08-05 10:00:01] [INFO] running source task
                [2026-08-05 10:00:03] [ERROR] connection refused
                """, StandardCharsets.UTF_8);

        JobInstance instance = JobInstance.builder()
                .id(4L)
                .clientId(2L)
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(4L)).thenReturn(instance);

        JobLogReplayResult replay = jobLogService.replay(4L, JobMode.BATCH);

        assertEquals(3, replay.totalSections());
        assertEquals(3, replay.totalSteps());
        assertEquals(3000L, replay.durationMs());
        assertEquals("任务操作", replay.sections().get(0).title());
        assertEquals(1L, replay.sections().get(0).steps().get(0).sequence());
        assertEquals("异常处理", replay.sections().get(2).title());
        assertEquals("记录异常", replay.sections().get(2).steps().get(0).operation());
        assertTrue(replay.sections().get(2).steps().get(0).detail().contains("connection refused"));
        assertEquals(1, replay.sections().get(2).steps().get(0).logs().size());
    }

    @Test
    void capsReplayStepsAtTenAndKeepsAllRawLogsInEachStep() throws Exception {
        Path logPath = tempDir.resolve("job-5.log");
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 15; index++) {
            content.append(switch (index % 3) {
                case 0 -> "[2026-08-05 10:00:00] [INFO] submit started";
                case 1 -> "[2026-08-05 10:00:01] [INFO] running source task";
                default -> "[2026-08-05 10:00:02] [ERROR] connection refused";
            }).append(System.lineSeparator());
        }
        Files.writeString(logPath, content, StandardCharsets.UTF_8);

        JobInstance instance = JobInstance.builder()
                .id(5L)
                .clientId(2L)
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(5L)).thenReturn(instance);

        JobLogReplayResult replay = jobLogService.replay(5L, JobMode.BATCH);

        assertTrue(replay.totalSteps() <= 10);
        assertEquals(15, replay.sections().stream()
                .flatMap(section -> section.steps().stream())
                .mapToInt(step -> step.logs().size())
                .sum());
        assertTrue(replay.sections().stream().allMatch(section -> section.steps().size() == 1));
    }

    private long count(String value, String target) {
        return value.lines().filter(line -> line.contains(target)).count();
    }

    private void awaitCollectionLockReleased(Path logPath) throws Exception {
        Path lockPath = logPath.resolveSibling(logPath.getFileName() + ".engine-state.lock");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.exists(lockPath)) {
                try (FileChannel channel = FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock != null) {
                        return;
                    }
                } catch (OverlappingFileLockException ignored) {
                    // The background collector still owns the process-local lock.
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Engine log collection lock was not released");
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
