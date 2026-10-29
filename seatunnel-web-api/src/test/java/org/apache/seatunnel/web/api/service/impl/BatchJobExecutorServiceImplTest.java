package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.metrics.BatchJobSubmitter;
import org.apache.seatunnel.web.api.lake.job.LakeJobGuard;
import org.apache.seatunnel.web.api.service.BatchJobDefinitionService;
import org.apache.seatunnel.web.api.service.BatchJobInstanceService;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.apache.seatunnel.web.api.service.JobScheduleService;
import org.apache.seatunnel.web.api.metrics.JobResultHandler;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.JobSubmitStage;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.common.exception.JobSubmitException;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.spi.bean.vo.BatchJobDefinitionVO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchJobExecutorServiceImplTest {

    @Test
    void manualExecutionQueuesSubmissionAndRecordsLastRunTimeOnItsSchedule() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        BatchJobDefinitionService definitionService = mock(BatchJobDefinitionService.class);
        BatchJobSubmitter jobSubmitter = mock(BatchJobSubmitter.class);
        IncrementalBatchService incrementalBatchService = mock(IncrementalBatchService.class);
        JobScheduleService jobScheduleService = mock(JobScheduleService.class);
        LakeJobGuard lakeJobGuard = mock(LakeJobGuard.class);
        JobResultHandler jobResultHandler = mock(JobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        TaskExecutor submitExecutor = queuedSubmissions::add;

        BatchJobExecutorServiceImpl executor = new BatchJobExecutorServiceImpl(
                instanceService,
                definitionService,
                jobSubmitter,
                incrementalBatchService,
                jobScheduleService,
                lakeJobGuard,
                jobResultHandler,
                submitExecutor
        );

        BatchJobDefinitionVO definition = new BatchJobDefinitionVO();
        definition.setId(301L);
        definition.setReleaseState(ReleaseState.ONLINE);

        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(401L);
        instance.setJobDefinitionId(301L);
        instance.setJobStatus(JobStatus.STARTING.name());
        instance.setRuntimeConfig("source { password = \"batch-secret\" }");
        JobInstanceVO publicLatest = new JobInstanceVO();
        publicLatest.setId(401L);
        publicLatest.setJobStatus(JobStatus.STARTING.name());
        publicLatest.setRuntimeConfig("source { password = \"***\" }");

        JobSchedule schedule = new JobSchedule();
        schedule.setId(501L);

        when(definitionService.selectById(301L)).thenReturn(definition);
        when(instanceService.selectById(401L)).thenReturn(publicLatest);
        when(incrementalBatchService.prepare(301L)).thenReturn(null);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, null)).thenReturn(instance);
        when(jobScheduleService.getByTaskDefinitionId(301L)).thenReturn(schedule);
        when(jobScheduleService.updateLastScheduleTime(501L)).thenReturn(true);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));

        assertEquals(1, queuedSubmissions.size());
        verifyNoInteractions(jobSubmitter);
        assertThrows(ServiceException.class, () -> executor.jobExecute(301L, RunMode.MANUAL));
        assertEquals(1, queuedSubmissions.size());

        queuedSubmissions.get(0).run();

        verify(jobSubmitter).submit(instance);
        assertEquals("source { password = \"batch-secret\" }", instance.getRuntimeConfig());
        assertEquals("source { password = \"***\" }", publicLatest.getRuntimeConfig());
        verify(jobScheduleService).updateLastScheduleTime(501L);
        verify(lakeJobGuard).validateBeforeExecute(301L, LakeJobRuntimeType.BATCH);
        verify(lakeJobGuard).validateBeforeSubmit(instance, LakeJobRuntimeType.BATCH);
        verify(incrementalBatchService).prepare(301L);
        verify(instanceService).createIfNoRunningInstance(301L, RunMode.MANUAL, null);
    }

    @Test
    void ambiguousSubmitFailureStaysBlockingInsteadOfBecomingRetryable() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        BatchJobDefinitionService definitionService = mock(BatchJobDefinitionService.class);
        BatchJobSubmitter jobSubmitter = mock(BatchJobSubmitter.class);
        JobResultHandler jobResultHandler = mock(JobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        TaskExecutor submitExecutor = queuedSubmissions::add;

        BatchJobExecutorServiceImpl executor = new BatchJobExecutorServiceImpl(
                instanceService,
                definitionService,
                jobSubmitter,
                mock(IncrementalBatchService.class),
                mock(JobScheduleService.class),
                mock(LakeJobGuard.class),
                jobResultHandler,
                submitExecutor
        );

        JobInstanceVO instance =
                queueSubmission(executor, instanceService, definitionService, queuedSubmissions);

        // The Engine may have accepted the job, so the instance must keep blocking instead of FAILED.
        doThrow(new JobSubmitException(
                JobSubmitStage.SUBMIT, "no Engine job id", new IOException("Read timed out"), true))
                .when(jobSubmitter).submit(instance);

        queuedSubmissions.get(0).run();

        verify(jobResultHandler, never()).handleFailure(anyLong(), any(Throwable.class));
    }

    @Test
    void definiteSubmitFailureIsStillRecordedAsFailed() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        BatchJobDefinitionService definitionService = mock(BatchJobDefinitionService.class);
        BatchJobSubmitter jobSubmitter = mock(BatchJobSubmitter.class);
        JobResultHandler jobResultHandler = mock(JobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        TaskExecutor submitExecutor = queuedSubmissions::add;

        BatchJobExecutorServiceImpl executor = new BatchJobExecutorServiceImpl(
                instanceService,
                definitionService,
                jobSubmitter,
                mock(IncrementalBatchService.class),
                mock(JobScheduleService.class),
                mock(LakeJobGuard.class),
                jobResultHandler,
                submitExecutor
        );

        JobInstanceVO instance =
                queueSubmission(executor, instanceService, definitionService, queuedSubmissions);

        // A refused connection proves the Engine never accepted the job, so FAILED is correct.
        doThrow(new JobSubmitException(
                JobSubmitStage.SUBMIT, "engine unreachable", new ConnectException("Connection refused")))
                .when(jobSubmitter).submit(instance);

        queuedSubmissions.get(0).run();

        verify(jobResultHandler).handleFailure(eq(401L), any(Throwable.class));
    }

    @Test
    void queuedSubmissionIsSkippedAfterCancelStopFindsNoEngineJobYet() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        BatchJobDefinitionService definitionService = mock(BatchJobDefinitionService.class);
        BatchJobSubmitter jobSubmitter = mock(BatchJobSubmitter.class);
        JobResultHandler jobResultHandler = mock(JobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        BatchJobExecutorServiceImpl executor = new BatchJobExecutorServiceImpl(
                instanceService,
                definitionService,
                jobSubmitter,
                mock(IncrementalBatchService.class),
                mock(JobScheduleService.class),
                mock(LakeJobGuard.class),
                jobResultHandler,
                queuedSubmissions::add
        );

        JobInstanceVO starting = queueSubmission(executor, instanceService,
                definitionService, queuedSubmissions);
        starting.setEngineJobId("401");
        JobInstanceVO canceling = new JobInstanceVO();
        canceling.setId(401L);
        canceling.setEngineJobId("401");
        canceling.setJobStatus(JobStatus.CANCELING.name());
        when(jobResultHandler.handleCanceling(
                eq(401L), eq("401"), any(String.class))).thenReturn(true);
        when(instanceService.selectById(401L)).thenReturn(starting, canceling);
        doThrow(new IllegalStateException("Engine job not found yet"))
                .when(jobSubmitter).pause(starting);

        executor.jobPause(401L);
        queuedSubmissions.get(0).run();

        verify(jobResultHandler).handleCanceling(eq(401L), eq("401"), any(String.class));
        verify(jobResultHandler).skipSubmissionIfNotStarting(401L, "401");
        verify(jobSubmitter).pause(starting);
        verify(jobSubmitter, never()).submit(any(JobInstanceVO.class));
    }

    private static BatchJobExecutorServiceImpl executorWith(BatchJobInstanceService instanceService) {
        return new BatchJobExecutorServiceImpl(
                instanceService,
                mock(BatchJobDefinitionService.class),
                mock(BatchJobSubmitter.class),
                mock(IncrementalBatchService.class),
                mock(JobScheduleService.class),
                mock(LakeJobGuard.class),
                mock(JobResultHandler.class),
                mock(TaskExecutor.class)
        );
    }

    /** Queues one submission through the executor and returns the created instance. */
    private static JobInstanceVO queueSubmission(BatchJobExecutorServiceImpl executor,
                                                 BatchJobInstanceService instanceService,
                                                 BatchJobDefinitionService definitionService,
                                                 List<Runnable> queuedSubmissions) {
        BatchJobDefinitionVO definition = new BatchJobDefinitionVO();
        definition.setId(301L);
        definition.setReleaseState(ReleaseState.ONLINE);

        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(401L);
        instance.setJobDefinitionId(301L);
        instance.setJobStatus(JobStatus.STARTING.name());

        when(definitionService.selectById(301L)).thenReturn(definition);
        when(instanceService.selectById(401L)).thenReturn(instance);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, null)).thenReturn(instance);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));
        assertEquals(1, queuedSubmissions.size());
        return instance;
    }
}
