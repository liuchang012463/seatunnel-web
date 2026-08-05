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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
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
        when(seaTunnelRestClient.jobLogs(2L, "engine-1", "json"))
                .thenReturn("engine failure: connection refused");

        jobLogService.persistEngineLog(1L, JobMode.BATCH);
        jobLogService.persistEngineLog(1L, JobMode.BATCH);

        String stored = Files.readString(logPath, StandardCharsets.UTF_8);
        assertTrue(stored.contains("submit"));
        assertTrue(stored.contains("engine failure: connection refused"));
        assertEquals(1, count(stored, "=== SEA TUNNEL ENGINE LOG SNAPSHOT"));
        assertTrue(jobLogService.getFullContent(1L, JobMode.BATCH).contains("engine failure"));
        verify(seaTunnelRestClient, times(3)).jobLogs(eq(2L), eq("engine-1"), eq("json"));
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
    }

    @Test
    void replaysAllParsedLinesInSequenceOrder() throws Exception {
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

        assertEquals(3, replay.totalSteps());
        assertEquals(3000L, replay.durationMs());
        assertEquals(1L, replay.steps().get(0).sequence());
        assertEquals("错误事件", replay.steps().get(2).title());
        assertTrue(replay.steps().get(2).detail().contains("connection refused"));
    }

    private long count(String value, String target) {
        return value.lines().filter(line -> line.contains(target)).count();
    }
}
