package org.apache.seatunnel.web.api.client.scheduler;

import org.apache.seatunnel.web.api.metrics.JobResultHandler;
import org.apache.seatunnel.web.api.metrics.JobRuntimeContext;
import org.apache.seatunnel.web.api.metrics.streaming.StreamingJobResultHandler;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.engine.client.handler.ZetaJobStatusHandler;
import org.apache.seatunnel.web.engine.client.modal.ZetaJobStatusResolveResult;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobInstanceRecoverySchedulerTest {

    private final JobInstanceDao instances = mock(JobInstanceDao.class);
    private final StreamingJobInstanceDao streamingInstances = mock(StreamingJobInstanceDao.class);
    private final ZetaJobStatusHandler engine = mock(ZetaJobStatusHandler.class);
    private final JobResultHandler results = mock(JobResultHandler.class);
    private final StreamingJobResultHandler streamingResults = mock(StreamingJobResultHandler.class);
    private final SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);

    @Test
    void lostSubmitResponseFindsRunningJobByReservedId() {
        JobInstance instance = instance(new Date(0));
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance));
        when(engine.resolve(2L, "101")).thenReturn(ZetaJobStatusResolveResult.running("RUNNING"));

        scheduler().recoverPeriodically();

        verify(results).updateEngineId(101L, "101");
        verify(results, never()).handleFailure(any(), any(Throwable.class));
    }

    @Test
    void lostSubmitResponseFindsFinishedJobAndPreservesItsResult() {
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance(new Date(0))));
        when(engine.resolve(2L, "101")).thenReturn(
                ZetaJobStatusResolveResult.finished(JobStatus.FINISHED, "FINISHED", null, "done"));

        scheduler().recoverPeriodically();

        verify(results).handleFinalStatusIfStarting(
                any(JobRuntimeContext.class), eq(JobStatus.FINISHED), eq(null), eq("101"), eq(new Date(0)));
    }

    @Test
    void unreachableEngineKeepsSubmissionActiveForNextAutomaticCheck() {
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance(new Date(0))));
        when(engine.resolve(2L, "101")).thenReturn(ZetaJobStatusResolveResult.unknown("connection refused"));

        scheduler().recoverPeriodically();

        verifyNoInteractions(results);
    }

    @Test
    void confirmedAbsentJobFailsAutomatically() {
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance(new Date(0))));
        when(engine.resolve(2L, "101")).thenReturn(ZetaJobStatusResolveResult.notFound("absent"));

        scheduler().recoverPeriodically();

        verify(results).handleFinalStatusIfStarting(
                any(JobRuntimeContext.class), eq(JobStatus.FAILED), any(), eq("101"), eq(new Date(0)));
    }

    @Test
    void absentJobThatWasBeingCanceledBecomesCanceled() {
        JobInstance instance = instance(new Date(0));
        instance.setJobStatus(JobStatus.CANCELING);
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance));
        when(engine.resolve(2L, "101")).thenReturn(ZetaJobStatusResolveResult.notFound("absent"));

        scheduler().recoverPeriodically();

        verify(results).handleFinalStatus(any(JobRuntimeContext.class), eq(JobStatus.CANCELED), any());
    }

    @Test
    void cancelingBatchKeepsItsReservedIdUntilLateAcceptedJobIsStopped() {
        JobInstance instance = instance(new Date(0));
        instance.setJobStatus(JobStatus.CANCELING);
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance), List.of(instance));
        when(engine.resolve(2L, "101")).thenReturn(
                ZetaJobStatusResolveResult.running("RUNNING"),
                ZetaJobStatusResolveResult.notFound("stopped"));

        scheduler().recoverPeriodically();

        verify(restClient).stopJob(2L, "101", false);
        verify(results, never()).handleFinalStatus(any(JobRuntimeContext.class), any(), any());

        scheduler().recoverPeriodically();

        verify(results).handleFinalStatus(any(JobRuntimeContext.class), eq(JobStatus.CANCELED), any());
    }

    @Test
    void cancelingStreamingKeepsItsReservedIdUntilLateAcceptedJobIsStopped() {
        StreamingJobInstance instance = streamingInstance();
        instance.setJobStatus(JobStatus.CANCELING);
        when(streamingInstances.listRunningLikeInstances()).thenReturn(List.of(instance), List.of(instance));
        when(engine.resolve(2L, "101")).thenReturn(
                ZetaJobStatusResolveResult.running("RUNNING"),
                ZetaJobStatusResolveResult.notFound("stopped"));

        scheduler().recoverPeriodically();

        verify(restClient).stopJob(2L, "101", false);
        verify(streamingInstances, never()).updateFinalStatusIfRunningLike(any(), any(), any());

        scheduler().recoverPeriodically();

        verify(streamingInstances).updateFinalStatusIfRunningLike(
                eq(101L), eq(JobStatus.CANCELED), any());
    }

    @Test
    void cancelingSubmissionWithLiveHeartbeatWaitsBeforeEngineLookup() {
        JobInstance batch = instance(new Date());
        batch.setJobStatus(JobStatus.CANCELING);
        StreamingJobInstance streaming = streamingInstance();
        streaming.setJobStatus(JobStatus.CANCELING);
        streaming.setUpdateTime(new Date());
        when(instances.listRunningLikeInstances()).thenReturn(List.of(batch));
        when(streamingInstances.listRunningLikeInstances()).thenReturn(List.of(streaming));

        scheduler().recoverPeriodically();

        verifyNoInteractions(engine, restClient);
    }

    @Test
    void absentJobThatWasTakingASavepointBecomesSavepointDone() {
        JobInstance instance = instance(new Date(0));
        instance.setJobStatus(JobStatus.DOING_SAVEPOINT);
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance));
        when(engine.resolve(2L, "101")).thenReturn(ZetaJobStatusResolveResult.notFound("absent"));

        scheduler().recoverPeriodically();

        verify(results).handleFinalStatus(
                any(JobRuntimeContext.class), eq(JobStatus.SAVEPOINT_DONE), any());
    }

    @Test
    void confirmedUnknowableJobIsReconciledInsteadOfSkippedForever() {
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance(new Date(0))));
        when(engine.resolve(2L, "101")).thenReturn(
                ZetaJobStatusResolveResult.finished(
                        JobStatus.UNKNOWABLE, "UNKNOWABLE", null, "engine lost the job"));

        scheduler().recoverPeriodically();

        verify(results).handleFinalStatusIfStarting(
                any(JobRuntimeContext.class), eq(JobStatus.UNKNOWABLE), any(), eq("101"), eq(new Date(0)));
    }

    @Test
    void inFlightSubmissionIsNotReconciledBeforeHeartbeatExpires() {
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance(new Date())));

        scheduler().recoverPeriodically();

        verifyNoInteractions(engine, results);
    }

    @Test
    void streamingEngineErrorIsMaskedBeforeItIsPersisted() {
        when(streamingInstances.listRunningLikeInstances()).thenReturn(List.of(streamingInstance()));
        when(engine.resolve(2L, "101")).thenReturn(
                ZetaJobStatusResolveResult.finished(
                        JobStatus.FAILED, "FAILED", "connect failed password=P@ssw0rd!", "job failed"));

        scheduler().recoverPeriodically();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(streamingInstances).updateFinalStatusIfRunningLike(eq(101L), eq(JobStatus.FAILED), message.capture());
        assertTrue(message.getValue().contains("engineError="));
        assertFalse(message.getValue().contains("P@ssw0rd"), "engine error must be masked: " + message.getValue());
    }

    @Test
    void confirmedUnknowableStreamingJobIsReconciledAsFailed() {
        when(streamingInstances.listRunningLikeInstances()).thenReturn(List.of(streamingInstance()));
        when(engine.resolve(2L, "101")).thenReturn(
                ZetaJobStatusResolveResult.finished(
                        JobStatus.UNKNOWABLE, "UNKNOWABLE", null, "engine lost the job"));

        scheduler().recoverPeriodically();

        verify(streamingInstances).updateFinalStatusIfRunningLike(eq(101L), eq(JobStatus.FAILED), any());
    }

    @Test
    void queuedStreamingSubmissionWaitsForItsHeartbeatBeforeReconciliation() {
        StreamingJobInstance instance = streamingInstance();
        instance.setJobStatus(JobStatus.STARTING);
        instance.setUpdateTime(new Date());
        when(streamingInstances.listRunningLikeInstances()).thenReturn(List.of(instance));

        scheduler().recoverPeriodically();

        verifyNoInteractions(engine, streamingResults);
    }

    @Test
    void lostStreamingSubmitResponseFindsReservedEngineId() {
        StreamingJobInstance instance = streamingInstance();
        instance.setJobStatus(JobStatus.STARTING);
        when(streamingInstances.listRunningLikeInstances()).thenReturn(List.of(instance));
        when(engine.resolve(2L, "101")).thenReturn(ZetaJobStatusResolveResult.running("RUNNING"));

        scheduler().recoverPeriodically();

        verify(streamingInstances).updateEngineIdIfStarting(eq(101L), eq("101"), any(Date.class));
        verify(streamingInstances, never()).updateFinalStatusIfRunningLike(any(), any(), any());
    }

    @Test
    void staleStartingBatchRecoveryUsesSnapshotCasForTerminalResult() {
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance(new Date(0))));
        when(engine.resolve(2L, "101")).thenReturn(ZetaJobStatusResolveResult.notFound("absent"));

        scheduler().recoverPeriodically();

        verify(results).handleFinalStatusIfStarting(
                any(JobRuntimeContext.class), eq(JobStatus.FAILED), any(), eq("101"), eq(new Date(0)));
        verify(results, never()).handleFinalStatus(any(JobRuntimeContext.class), any(), any());
    }

    @Test
    void staleStartingStreamingRecoveryUsesSnapshotCasForTerminalResult() {
        StreamingJobInstance instance = streamingInstance();
        instance.setJobStatus(JobStatus.STARTING);
        when(streamingInstances.listRunningLikeInstances()).thenReturn(List.of(instance));
        when(engine.resolve(2L, "101")).thenReturn(ZetaJobStatusResolveResult.notFound("absent"));

        scheduler().recoverPeriodically();

        verify(streamingResults).handleFinalStatusIfStarting(
                eq(101L), eq("101"), eq(new Date(0)), eq(JobStatus.FAILED), any());
        verify(streamingInstances, never()).updateFinalStatusIfRunningLike(any(), any(), any());
    }

    private JobInstanceRecoveryScheduler scheduler() {
        JobInstanceRecoveryScheduler scheduler = new JobInstanceRecoveryScheduler();
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "jobInstanceDao", instances);
        ReflectionTestUtils.setField(scheduler, "streamingJobInstanceDao", streamingInstances);
        ReflectionTestUtils.setField(scheduler, "zetaJobStatusHandler", engine);
        ReflectionTestUtils.setField(scheduler, "seaTunnelRestClient", restClient);
        ReflectionTestUtils.setField(scheduler, "jobResultHandler", results);
        ReflectionTestUtils.setField(scheduler, "streamingJobResultHandler", streamingResults);
        return scheduler;
    }

    private JobInstance instance(Date lastActivity) {
        JobInstance instance = new JobInstance();
        instance.setId(101L);
        instance.setClientId(2L);
        instance.setEngineJobId("101");
        instance.setJobStatus(JobStatus.STARTING);
        instance.setUpdateTime(lastActivity);
        return instance;
    }

    private StreamingJobInstance streamingInstance() {
        StreamingJobInstance instance = new StreamingJobInstance();
        instance.setId(101L);
        instance.setClientId(2L);
        instance.setEngineJobId("101");
        instance.setJobStatus(JobStatus.RUNNING);
        instance.setUpdateTime(new Date(0));
        return instance;
    }
}
