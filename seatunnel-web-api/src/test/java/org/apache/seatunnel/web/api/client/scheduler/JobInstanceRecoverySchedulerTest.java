package org.apache.seatunnel.web.api.client.scheduler;

import org.apache.seatunnel.web.api.metrics.JobResultHandler;
import org.apache.seatunnel.web.api.metrics.JobRuntimeContext;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.engine.client.handler.ZetaJobStatusHandler;
import org.apache.seatunnel.web.engine.client.modal.ZetaJobStatusResolveResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobInstanceRecoverySchedulerTest {

    private final JobInstanceDao instances = mock(JobInstanceDao.class);
    private final ZetaJobStatusHandler engine = mock(ZetaJobStatusHandler.class);
    private final JobResultHandler results = mock(JobResultHandler.class);

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

        verify(results).handleFinalStatus(any(JobRuntimeContext.class), eq(JobStatus.FINISHED), eq(null));
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

        verify(results).handleFinalStatus(any(JobRuntimeContext.class), eq(JobStatus.FAILED), any());
    }

    @Test
    void inFlightSubmissionIsNotReconciledBeforeHeartbeatExpires() {
        when(instances.listRunningLikeInstances()).thenReturn(List.of(instance(new Date())));

        scheduler().recoverPeriodically();

        verifyNoInteractions(engine, results);
    }

    private JobInstanceRecoveryScheduler scheduler() {
        JobInstanceRecoveryScheduler scheduler = new JobInstanceRecoveryScheduler();
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "jobInstanceDao", instances);
        ReflectionTestUtils.setField(scheduler, "streamingJobInstanceDao", mock(StreamingJobInstanceDao.class));
        ReflectionTestUtils.setField(scheduler, "zetaJobStatusHandler", engine);
        ReflectionTestUtils.setField(scheduler, "jobResultHandler", results);
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
}
