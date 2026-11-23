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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
        CountDownLatch fetched = new CountDownLatch(1);
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-1"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(2);
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
        verify(seaTunnelRestClient, times(1))
                .writeMasterJobLogFile(eq(2L), eq("engine-1"), any(OutputStream.class));
    }

    @Test
    void appendsOnlyNewEngineLogBytesAndPacesActiveCollectionButRunsTerminalCollection() throws Exception {
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
        AtomicReference<String> engineLog = new AtomicReference<>("first line\n");
        CountDownLatch firstFetchCompleted = new CountDownLatch(1);
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-7"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = engineLog.get().getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    firstFetchCompleted.countDown();
                    return (long) bytes.length;
                });

        jobLogService.persistEngineLog(7L, JobMode.BATCH);
        jobLogService.persistEngineLog(7L, JobMode.BATCH);
        assertTrue(firstFetchCompleted.await(2, TimeUnit.SECONDS));
        awaitCollectionLockReleased(logPath);
        verify(seaTunnelRestClient, times(1)).writeMasterJobLogFile(eq(2L), eq("engine-7"), any(OutputStream.class));

        instance.setJobStatus(JobStatus.FINISHED);
        engineLog.set("first line\nsecond line\n");
        jobLogService.persistFinalEngineLog(7L, JobMode.BATCH);
        jobLogService.persistFinalEngineLog(7L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, stored.lines().filter(line -> line.contains("first line")).count());
        assertTrue(stored.contains("second line"));
        assertTrue(stored.contains("delta=true fromBytes="));
        verify(seaTunnelRestClient, times(3)).writeMasterJobLogFile(eq(2L), eq("engine-7"), any(OutputStream.class));
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
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-10"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    remoteReadStarted.countDown();
                    if (!releaseRemoteRead.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release the Engine fixture");
                    }
                    return 0L;
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
            verify(seaTunnelRestClient, times(1)).writeMasterJobLogFile(eq(2L), eq("engine-10"), any(OutputStream.class));
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

        AtomicReference<String> engineLog = new AtomicReference<>("first line\n");
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-11"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = engineLog.get().getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    return (long) bytes.length;
                });

        jobLogService.persistFinalEngineLog(11L, JobMode.BATCH);
        engineLog.set("first line\nsecond line\n");
        String updatedSnapshot = engineLog.get();
        String hash = sha256(updatedSnapshot);
        String completedFrame = System.lineSeparator()
                + "=== SEA TUNNEL ENGINE LOG SNAPSHOT sha256=" + hash
                + " node=__master_shared_logs__ delta=true fromBytes=11 engineJobId=engine-11 ==="
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

        AtomicReference<String> engineLog = new AtomicReference<>("first line\n");
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-12"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = engineLog.get().getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    return (long) bytes.length;
                });

        jobLogService.persistFinalEngineLog(12L, JobMode.BATCH);
        Files.writeString(logPath, "web event after Engine output\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        engineLog.set("first line\nsecond line\n");
        String hash = sha256(engineLog.get());
        String incompleteFrame = System.lineSeparator()
                + "=== SEA TUNNEL ENGINE LOG SNAPSHOT sha256=" + hash
                + " node=__master_shared_logs__ delta=true fromBytes=11 engineJobId=engine-12 ==="
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
        verify(seaTunnelRestClient, times(2)).writeMasterJobLogFile(eq(2L), eq("engine-12"), any(OutputStream.class));
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
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-13"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    remoteReadStarted.countDown();
                    if (!releaseRemoteRead.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release the Engine fixture");
                    }
                    return 0L;
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

    @Test
    void downloadsThroughTheConfiguredEndpointOncePerRoundWithoutListingNodes() throws Exception {
        Path logPath = tempDir.resolve("job-20.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(20L)
                .clientId(2L)
                .engineJobId("engine-20")
                .jobStatus(JobStatus.RUNNING)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(20L)).thenReturn(instance);
        AtomicReference<String> sharedLog = new AtomicReference<>("shared line one\n");
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-20"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = sharedLog.get().getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    return (long) bytes.length;
                });

        jobLogService.persistEngineLog(20L, JobMode.BATCH);
        awaitCollectionLockReleased(logPath);
        jobLogService.persistEngineLog(20L, JobMode.BATCH);
        awaitCollectionLockReleased(logPath);
        verify(seaTunnelRestClient, times(1)).writeMasterJobLogFile(
                eq(2L), eq("engine-20"), any(OutputStream.class));

        instance.setJobStatus(JobStatus.FINISHED);
        sharedLog.set("shared line one\nshared line two\n");
        jobLogService.persistFinalEngineLog(20L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, count(stored, "shared line one"));
        assertEquals(1, count(stored, "shared line two"));
        assertTrue(stored.contains("node=__master_shared_logs__"));
        assertTrue(stored.contains("delta=true fromBytes="));
        assertTrue(Files.readString(logPath.resolveSibling("job-20.log.engine-state"), StandardCharsets.UTF_8)
                .contains("terminalCollected=false"));

        // A second read of the unchanged terminal file confirms the stable snapshot, then the
        // collected terminal stops contacting Engine at all.
        jobLogService.persistFinalEngineLog(20L, JobMode.BATCH);
        assertTrue(Files.readString(logPath.resolveSibling("job-20.log.engine-state"), StandardCharsets.UTF_8)
                .contains("terminalCollected=true"));
        jobLogService.persistFinalEngineLog(20L, JobMode.BATCH);

        verify(seaTunnelRestClient, times(3)).writeMasterJobLogFile(
                eq(2L), eq("engine-20"), any(OutputStream.class));
        verify(seaTunnelRestClient, never()).jobLogFiles(anyLong(), anyString());
        verify(seaTunnelRestClient, never()).writeJobLogFile(
                anyLong(), anyString(), any(SeaTunnelRestClient.NodeLogFile.class), any(OutputStream.class));
    }

    @Test
    void reusesAVerifiedLegacyNodeCursor() throws Exception {
        Path logPath = tempDir.resolve("job-21.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(21L)
                .clientId(2L)
                .engineJobId("engine-21")
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(21L)).thenReturn(instance);
        writeLegacyNodeArchive(logPath, "engine-21", "first line\n");

        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-21"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = "first line\nsecond line\n".getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    return (long) bytes.length;
                });
        jobLogService.persistFinalEngineLog(21L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, count(stored, "first line"));
        assertEquals(1, count(stored, "second line"));
        assertTrue(stored.contains("delta=true fromBytes=11"));
        assertTrue(stored.contains("node=__master_shared_logs__"));
        verify(seaTunnelRestClient, times(1)).writeMasterJobLogFile(
                eq(2L), eq("engine-21"), any(OutputStream.class));
    }

    @Test
    void doesNotReuseAMismatchedLegacyNodeCursor() throws Exception {
        Path logPath = tempDir.resolve("job-22.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(22L)
                .clientId(2L)
                .engineJobId("engine-22")
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(22L)).thenReturn(instance);
        writeLegacyNodeArchive(logPath, "engine-22", "old node content\n");

        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-22"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    byte[] bytes = "different shared content\n".getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    return (long) bytes.length;
                });
        jobLogService.persistFinalEngineLog(22L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, count(stored, "old node content"));
        assertEquals(1, count(stored, "different shared content"));
        assertTrue(stored.contains("node=__master_shared_logs__"));
        // The prefix hash did not match, so the shared file is archived from its beginning.
        assertTrue(!stored.contains("delta=true fromBytes="));
    }

    @Test
    void downloadFailureKeepsTheArchivedContentAndCursorForTheNextRetry() throws Exception {
        Path logPath = tempDir.resolve("job-23.log");
        Files.writeString(logPath, "web start\n", StandardCharsets.UTF_8);
        JobInstance instance = JobInstance.builder()
                .id(23L)
                .clientId(2L)
                .engineJobId("engine-23")
                .jobStatus(JobStatus.FINISHED)
                .logPath(logPath.toString())
                .build();
        when(jobInstanceDao.queryById(23L)).thenReturn(instance);
        AtomicInteger attempts = new AtomicInteger();
        when(seaTunnelRestClient.writeMasterJobLogFile(eq(2L), eq("engine-23"), any(OutputStream.class)))
                .thenAnswer(invocation -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 2) {
                        throw new IllegalStateException("engine rest endpoint unavailable");
                    }
                    String content = attempt == 1 ? "first line\n" : "first line\nsecond line\n";
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    ((OutputStream) invocation.getArgument(2)).write(bytes);
                    return (long) bytes.length;
                });

        jobLogService.persistFinalEngineLog(23L, JobMode.BATCH);
        String firstFetch = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, count(firstFetch, "first line"));

        jobLogService.persistFinalEngineLog(23L, JobMode.BATCH);
        assertEquals(firstFetch, Files.readString(logPath, StandardCharsets.UTF_8));

        jobLogService.persistFinalEngineLog(23L, JobMode.BATCH);
        String recovered = Files.readString(logPath, StandardCharsets.UTF_8);
        assertEquals(1, count(recovered, "first line"));
        assertEquals(1, count(recovered, "second line"));
        assertTrue(recovered.contains("delta=true fromBytes=11"));
        assertEquals(3, attempts.get());
    }

    private void writeLegacyNodeArchive(Path logPath, String engineJobId, String content) throws Exception {
        String node = "master:8080";
        String hash = sha256(content);
        Files.writeString(logPath, """

                === SEA TUNNEL ENGINE LOG SNAPSHOT sha256=%s node=%s engineJobId=%s ===
                %s
                === END SEA TUNNEL ENGINE LOG SNAPSHOT ===
                """.formatted(hash, node, engineJobId, content), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        String prefix = "node." + sha256(node);
        Path statePath = logPath.resolveSibling(logPath.getFileName() + ".engine-state");
        Files.writeString(statePath, """
                formatVersion=2
                terminalCollected=false
                %s.name=%s
                %s.bytes=%d
                %s.hash=%s
                """.formatted(prefix, node, prefix, content.getBytes(StandardCharsets.UTF_8).length, prefix, hash),
                StandardCharsets.UTF_8);
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
