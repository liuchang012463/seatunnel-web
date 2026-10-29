package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.job.LakeJobGuard;
import org.apache.seatunnel.web.api.metrics.streaming.StreamingJobResultHandler;
import org.apache.seatunnel.web.api.metrics.streaming.StreamingJobSubmitter;
import org.apache.seatunnel.web.api.service.StreamingJobInstanceService;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.common.enums.JobSubmitStage;
import org.apache.seatunnel.web.common.exception.JobSubmitException;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.engine.client.handler.ZetaJobStatusHandler;
import org.apache.seatunnel.web.engine.client.modal.ZetaJobStatusResolveResult;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamingJobExecutorServiceImplTest {

    @Test
    void executeQueuesSubmissionAndRejectsDuplicateClickWhileQueued() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        ZetaJobStatusHandler statusHandler = mock(ZetaJobStatusHandler.class);
        LakeJobGuard guard = mock(LakeJobGuard.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        TaskExecutor submitExecutor = queuedSubmissions::add;

        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, statusHandler,
                guard, resultHandler, submitExecutor);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        JobInstanceVO instance = instance(401L);
        instance.setRuntimeConfig("source { password = \"stream-secret\" }");
        JobInstanceVO publicLatest = instance(401L);
        publicLatest.setRuntimeConfig("source { password = \"***\" }");
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(instance);
        when(instanceService.selectById(401L)).thenReturn(publicLatest);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));

        assertEquals(1, queuedSubmissions.size());
        verifyNoInteractions(submitter);
        assertThrows(ServiceException.class, () -> executor.jobExecute(301L, RunMode.MANUAL));

        queuedSubmissions.get(0).run();

        var order = inOrder(guard, instanceService, submitter);
        order.verify(guard).validateBeforeExecute(301L, LakeJobRuntimeType.STREAMING);
        order.verify(instanceService).createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING);
        order.verify(guard).validateBeforeSubmit(instance, LakeJobRuntimeType.STREAMING);
        order.verify(submitter).submit(instance);
        assertEquals("source { password = \"stream-secret\" }", instance.getRuntimeConfig());
        assertEquals("source { password = \"***\" }", publicLatest.getRuntimeConfig());
    }

    @Test
    void resumeFromSavepointUsesTheSharedSubmissionQueue() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        ZetaJobStatusHandler statusHandler = mock(ZetaJobStatusHandler.class);
        LakeJobGuard guard = mock(LakeJobGuard.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        TaskExecutor submitExecutor = queuedSubmissions::add;

        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, statusHandler,
                guard, resultHandler, submitExecutor);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        JobInstanceVO source = new JobInstanceVO();
        source.setId(501L);
        source.setJobDefinitionId(301L);
        source.setJobStatus(JobStatus.CANCELED.name());
        source.setEngineJobId("engine-1");
        source.setSavepointPath("zeta://savepoint/job/engine-1");
        when(instanceService.selectById(501L)).thenReturn(source);

        JobInstanceVO replacement = instance(601L);
        replacement.setRuntimeConfig("source { password = \"restore-secret\" }");
        JobInstanceVO publicReplacement = instance(601L);
        publicReplacement.setRuntimeConfig("source { password = \"***\" }");
        // Engine 3.0 reuses the source job id as the Engine id for SAVEPOINT restores.
        replacement.setEngineJobId("engine-1");
        publicReplacement.setEngineJobId("engine-1");
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(replacement);
        when(instanceService.selectById(601L)).thenReturn(publicReplacement);

        assertEquals(601L, executor.jobResumeFromSavepoint(501L, RunMode.MANUAL));

        assertEquals(1, queuedSubmissions.size());
        verifyNoInteractions(submitter);
        queuedSubmissions.get(0).run();

        var order = inOrder(guard, instanceService, submitter);
        order.verify(guard).validateBeforeExecute(301L, LakeJobRuntimeType.STREAMING);
        order.verify(instanceService).createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING);
        order.verify(instanceService).updateById(any(StreamingJobInstance.class));
        order.verify(guard).validateBeforeSubmit(replacement, LakeJobRuntimeType.STREAMING);
        order.verify(submitter).submitFromSavepoint(replacement, "engine-1");
        assertEquals("source { password = \"restore-secret\" }", replacement.getRuntimeConfig());
        assertEquals("source { password = \"***\" }", publicReplacement.getRuntimeConfig());
    }

    @Test
    void previousEngineStatusIsResolvedOnTheWorkerBeforeNewSubmission() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        ZetaJobStatusHandler statusHandler = mock(ZetaJobStatusHandler.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, statusHandler,
                mock(LakeJobGuard.class), resultHandler, queuedSubmissions::add);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        StreamingJobInstance previous = new StreamingJobInstance();
        previous.setId(201L);
        previous.setClientId(2L);
        previous.setEngineJobId("engine-201");
        when(instanceService.lastInstance(301L)).thenReturn(previous);
        when(statusHandler.resolve(2L, "engine-201"))
                .thenReturn(ZetaJobStatusResolveResult.running("RUNNING"));
        when(instanceService.updateStatusIfSnapshot(
                eq(201L), isNull(), eq("engine-201"), isNull(), eq(JobStatus.RUNNING), anyString()))
                .thenReturn(true);
        JobInstanceVO instance = instance(401L);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(instance);
        when(instanceService.selectById(401L)).thenReturn(instance);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));

        verifyNoInteractions(statusHandler);
        verifyNoInteractions(submitter);
        queuedSubmissions.get(0).run();

        verify(statusHandler).resolve(2L, "engine-201");
        verify(instanceService).updateStatusIfSnapshot(
                eq(201L), isNull(), eq("engine-201"), isNull(), eq(JobStatus.RUNNING), anyString());
        verify(resultHandler).handleFailure(eq(401L), any(Throwable.class));
        verify(submitter, never()).submit(any(JobInstanceVO.class));
    }

    @Test
    void unknownPreviousEngineStatusKeepsNewInstanceStartingForRecovery() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        ZetaJobStatusHandler statusHandler = mock(ZetaJobStatusHandler.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, statusHandler,
                mock(LakeJobGuard.class), resultHandler, queuedSubmissions::add);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        StreamingJobInstance previous = new StreamingJobInstance();
        previous.setId(201L);
        previous.setClientId(2L);
        previous.setEngineJobId("engine-201");
        when(instanceService.lastInstance(301L)).thenReturn(previous);
        when(statusHandler.resolve(2L, "engine-201"))
                .thenReturn(ZetaJobStatusResolveResult.unknown("Engine is unavailable"));
        JobInstanceVO instance = instance(401L);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(instance);
        when(instanceService.selectById(401L)).thenReturn(instance);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));
        verifyNoInteractions(statusHandler);
        queuedSubmissions.get(0).run();

        verify(instanceService).replaceEngineIdIfStarting(
                eq(401L), eq("401"), eq("engine-201"), anyString());
        verify(resultHandler, never()).handleFailure(anyLong(), any(Throwable.class));
        verify(submitter, never()).submit(any(JobInstanceVO.class));
    }

    @Test
    void confirmedUnknowablePreviousEngineStatusAllowsNewSubmission() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        ZetaJobStatusHandler statusHandler = mock(ZetaJobStatusHandler.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, statusHandler,
                mock(LakeJobGuard.class), resultHandler, queuedSubmissions::add);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        StreamingJobInstance previous = new StreamingJobInstance();
        previous.setId(201L);
        previous.setClientId(2L);
        previous.setEngineJobId("engine-201");
        when(instanceService.lastInstance(301L)).thenReturn(previous);
        when(statusHandler.resolve(2L, "engine-201")).thenReturn(
                ZetaJobStatusResolveResult.finished(
                        JobStatus.UNKNOWABLE, "UNKNOWABLE", null, "Engine confirms job is absent"));
        JobInstanceVO instance = instance(401L);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(instance);
        when(instanceService.selectById(401L)).thenReturn(instance);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));
        queuedSubmissions.get(0).run();

        verify(submitter).submit(instance);
        verify(instanceService, never()).replaceEngineIdIfStarting(anyLong(), anyString(), anyString(), anyString());
        verify(resultHandler, never()).handleFailure(anyLong(), any(Throwable.class));
    }

    @Test
    void cancelDuringUnknownPreviousEngineLookupHandsOffOldIdWithoutEndingCancellation() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        ZetaJobStatusHandler statusHandler = mock(ZetaJobStatusHandler.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, statusHandler,
                mock(LakeJobGuard.class), resultHandler, queuedSubmissions::add);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        StreamingJobInstance previous = new StreamingJobInstance();
        previous.setId(201L);
        previous.setClientId(2L);
        previous.setEngineJobId("engine-201");
        when(instanceService.lastInstance(301L)).thenReturn(previous);
        JobInstanceVO starting = instance(401L);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(starting);
        when(instanceService.selectById(401L)).thenReturn(starting, starting);
        doAnswer(invocation -> {
            starting.setJobStatus(JobStatus.CANCELING.name());
            return true;
        }).when(resultHandler).handleCanceling(eq(401L), eq("401"), anyString());
        doAnswer(invocation -> {
            starting.setEngineJobId(invocation.getArgument(2));
            return true;
        }).when(instanceService).replaceEngineIdIfStarting(
                eq(401L), eq("401"), eq("engine-201"), anyString());
        doAnswer(invocation -> {
            executor.jobPause(401L);
            return ZetaJobStatusResolveResult.unknown("Engine is unavailable");
        }).when(statusHandler).resolve(2L, "engine-201");

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));
        queuedSubmissions.get(0).run();

        verify(resultHandler).handleCanceling(eq(401L), eq("401"), anyString());
        verify(instanceService).replaceEngineIdIfStarting(eq(401L), eq("401"), eq("engine-201"), anyString());
        verify(resultHandler, never()).skipSubmissionIfNotStarting(anyLong(), anyString());
        assertEquals(JobStatus.CANCELING.name(), starting.getJobStatus());
        assertEquals("engine-201", starting.getEngineJobId());
        verify(submitter).pause(starting);
        verify(submitter, never()).submit(any(JobInstanceVO.class));
    }

    @Test
    void ambiguousSubmitFailureLeavesTheInstanceForAutomaticReconciliation() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, mock(ZetaJobStatusHandler.class),
                mock(LakeJobGuard.class), resultHandler, queuedSubmissions::add);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        JobInstanceVO instance = instance(401L);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(instance);
        when(instanceService.selectById(401L)).thenReturn(instance);
        doThrow(new JobSubmitException(
                JobSubmitStage.SUBMIT, "no Engine job id", new IOException("Read timed out"), true))
                .when(submitter).submit(instance);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));
        queuedSubmissions.get(0).run();

        verify(resultHandler, never()).handleFailure(anyLong(), any(Throwable.class));
        verify(instanceService, never()).updateById(any(StreamingJobInstance.class));
    }

    @Test
    void queueRejectionFailsTheReservedInstance() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("submission queue is full");
        };
        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, mock(StreamingJobSubmitter.class),
                mock(ZetaJobStatusHandler.class), mock(LakeJobGuard.class),
                resultHandler, rejectingExecutor);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        JobInstanceVO instance = instance(401L);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(instance);

        assertThrows(TaskRejectedException.class, () -> executor.jobExecute(301L, RunMode.MANUAL));

        verify(resultHandler).handleFailure(eq(401L), any(Throwable.class));
    }

    @Test
    void queuedSubmissionIsSkippedAfterCancelStopFindsNoEngineJobYet() {
        StreamingJobInstanceService instanceService = mock(StreamingJobInstanceService.class);
        StreamingJobDefinitionQueryService definitionQueryService =
                mock(StreamingJobDefinitionQueryService.class);
        StreamingJobSubmitter submitter = mock(StreamingJobSubmitter.class);
        StreamingJobResultHandler resultHandler = mock(StreamingJobResultHandler.class);
        List<Runnable> queuedSubmissions = new ArrayList<>();
        StreamingJobExecutorServiceImpl executor = new StreamingJobExecutorServiceImpl(
                instanceService, definitionQueryService, submitter, mock(ZetaJobStatusHandler.class),
                mock(LakeJobGuard.class), resultHandler, queuedSubmissions::add);
        prepareOnlineDefinition(definitionQueryService, instanceService);

        JobInstanceVO starting = instance(401L);
        JobInstanceVO canceling = instance(401L);
        canceling.setJobStatus(JobStatus.CANCELING.name());
        when(resultHandler.handleCanceling(
                eq(401L), eq("401"), any(String.class))).thenReturn(true);
        when(instanceService.createIfNoRunningInstance(301L, RunMode.MANUAL, JobMode.STREAMING))
                .thenReturn(starting);
        when(instanceService.selectById(401L)).thenReturn(starting, canceling);
        doThrow(new IllegalStateException("Engine job not found yet"))
                .when(submitter).pause(starting);

        assertEquals(401L, executor.jobExecute(301L, RunMode.MANUAL));
        executor.jobPause(401L);
        queuedSubmissions.get(0).run();

        verify(resultHandler).handleCanceling(eq(401L), eq("401"), any(String.class));
        verify(resultHandler).skipSubmissionIfNotStarting(401L, "401");
        verify(submitter).pause(starting);
        verify(submitter, never()).submit(any(JobInstanceVO.class));
    }

    private static void prepareOnlineDefinition(
            StreamingJobDefinitionQueryService definitionQueryService,
            StreamingJobInstanceService instanceService) {
        StreamingJobDefinitionEntity definition = new StreamingJobDefinitionEntity();
        definition.setId(301L);
        definition.setReleaseState(ReleaseState.ONLINE);
        when(definitionQueryService.getDefinitionOrThrow(301L)).thenReturn(definition);
        when(instanceService.existsRunningInstance(301L)).thenReturn(false);
        when(instanceService.lastInstance(301L)).thenReturn(null);
    }

    private static JobInstanceVO instance(Long id) {
        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(id);
        instance.setJobDefinitionId(301L);
        instance.setEngineJobId(String.valueOf(id));
        instance.setJobStatus(JobStatus.STARTING.name());
        return instance;
    }
}
