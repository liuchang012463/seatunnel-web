package org.apache.seatunnel.web.api.metrics;

import org.apache.seatunnel.web.api.log.JobLogService;
import org.apache.seatunnel.web.api.service.BatchJobInstanceService;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.apache.seatunnel.web.engine.client.exceptions.SeaTunnelClientException;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobResultHandlerTest {

    @Test
    void rejectedSubmissionKeepsEngineReasonAndMasksCredentials() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        JobResultHandler handler = handler(instanceService);

        handler.handleFailure(11L, new SeaTunnelClientException(
                "POST /submit-job/upload failed", 500,
                "Factory initialize failed: unsupported varcharbyte; password=private-test-value",
                new IllegalStateException("source rejected")));

        verify(instanceService).updateFinalStatusIfRunningLike(eq(11L), eq(JobStatus.FAILED),
                argThat(message -> message.contains("unsupported varcharbyte")
                        && !message.contains("private-test-value")));
    }

    @Test
    void savepointDoneIsPersistedAsItself() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        JobResultHandler handler = handler(instanceService);
        when(instanceService.updateFinalStatusIfRunningLike(
                eq(11L), eq(JobStatus.SAVEPOINT_DONE), anyString())).thenReturn(true);

        handler.handleFinalStatus(11L, JobStatus.SAVEPOINT_DONE, "savepoint completed");

        verify(instanceService).updateFinalStatusIfRunningLike(
                eq(11L), eq(JobStatus.SAVEPOINT_DONE), anyString());
        verify(instanceService, never()).updateFinalStatusIfRunningLike(
                eq(11L), eq(JobStatus.FAILED), anyString());
    }

    @Test
    void engineFailureMessageIsMaskedBeforeItIsPersisted() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        JobResultHandler handler = handler(instanceService);
        when(instanceService.updateFinalStatusIfRunningLike(
                eq(11L), eq(JobStatus.FAILED), anyString())).thenReturn(true);

        handler.handleFinalStatus(
                11L, JobStatus.FAILED, "connect failed; password=private-test-value");

        verify(instanceService).updateFinalStatusIfRunningLike(
                eq(11L),
                eq(JobStatus.FAILED),
                argThat(message -> message.contains("connect failed")
                        && !message.contains("private-test-value")));
    }

    @Test
    void lateSubmissionSuccessDoesNotOverwriteAResolvedInstance() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        JobResultHandler handler = handler(instanceService);
        when(instanceService.updateEngineIdIfStarting(eq(11L), eq("engine-1"), any(Date.class)))
                .thenReturn(false);

        handler.updateEngineId(11L, "engine-1");

        // The recovery scheduler or an operator already moved the instance out of STARTING.
        verify(instanceService, never()).updateById(any());
    }

    @Test
    void lateSubmissionSuccessCancelsAnOrphanedEngineJob() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        JobResultHandler handler = handler(
                instanceService, mock(ApplicationEventPublisher.class), restClient);
        when(instanceService.updateEngineIdIfStarting(eq(11L), eq("engine-1"), any(Date.class)))
                .thenReturn(false);

        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(11L);
        instance.setClientId(2L);
        instance.setJobStatus(JobStatus.FAILED.name());
        when(instanceService.selectById(11L)).thenReturn(instance);

        handler.updateEngineId(11L, "engine-1");

        // The Web already told the operator the job failed; the Engine job must not keep running.
        verify(restClient).stopJob(2L, "engine-1", false);
    }

    @Test
    void lateSubmissionSuccessRetriesStopWhileCancellationIsPending() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        JobResultHandler handler = handler(
                instanceService, mock(ApplicationEventPublisher.class), restClient);
        when(instanceService.updateEngineIdIfStarting(eq(11L), eq("engine-1"), any(Date.class)))
                .thenReturn(false);

        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(11L);
        instance.setClientId(2L);
        instance.setJobStatus(JobStatus.CANCELING.name());
        when(instanceService.selectById(11L)).thenReturn(instance);

        handler.updateEngineId(11L, "engine-1");

        verify(restClient).stopJob(2L, "engine-1", false);
    }

    @Test
    void lateSubmissionSuccessDoesNotCancelAJobTheWebAlreadyFinished() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);
        JobResultHandler handler = handler(
                instanceService, mock(ApplicationEventPublisher.class), restClient);
        when(instanceService.updateEngineIdIfStarting(eq(11L), eq("engine-1"), any(Date.class)))
                .thenReturn(false);

        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(11L);
        instance.setClientId(2L);
        instance.setJobStatus(JobStatus.FINISHED.name());
        when(instanceService.selectById(11L)).thenReturn(instance);

        handler.updateEngineId(11L, "engine-1");

        verify(restClient, never()).stopJob(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void submissionSuccessUsesTheConditionalUpdate() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        JobResultHandler handler = handler(instanceService);
        when(instanceService.updateEngineIdIfStarting(eq(11L), eq("engine-1"), any(Date.class)))
                .thenReturn(true);

        handler.updateEngineId(11L, "engine-1");

        verify(instanceService).updateEngineIdIfStarting(eq(11L), eq("engine-1"), any(Date.class));
        verify(instanceService, never()).updateById(any());
    }

    @Test
    void finalStatusUsesTheRunningLikeConditionalUpdate() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        JobResultHandler handler = handler(instanceService, publisher);
        when(instanceService.updateFinalStatusIfRunningLike(
                eq(11L),
                eq(org.apache.seatunnel.web.common.enums.JobStatus.FINISHED),
                isNull()))
                .thenReturn(true);

        handler.handleSuccess(11L);

        verify(instanceService).updateFinalStatusIfRunningLike(
                eq(11L),
                eq(org.apache.seatunnel.web.common.enums.JobStatus.FINISHED),
                isNull());
        verify(publisher).publishEvent(any());
    }

    @Test
    void lateFinalStatusDoesNotOverwriteATerminalDecision() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        JobResultHandler handler = handler(instanceService, publisher);
        when(instanceService.updateFinalStatusIfRunningLike(
                eq(11L),
                eq(org.apache.seatunnel.web.common.enums.JobStatus.FINISHED),
                isNull()))
                .thenReturn(false);

        handler.handleSuccess(11L);

        // The instance was already resolved (for example an ambiguous submission marked FAILED);
        // a late Engine success must not flip it or emit a second status change.
        verify(publisher, never()).publishEvent(any());
    }

    /**
     * The incremental batch record is a second sink of the engine error text, so it has to
     * receive the masked message as well.
     */
    @Test
    void theIncrementalBatchRecordReceivesTheMaskedMessage() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        IncrementalBatchService incrementalBatchService = mock(IncrementalBatchService.class);
        JobResultHandler handler = handler(
                instanceService,
                mock(ApplicationEventPublisher.class),
                mock(SeaTunnelRestClient.class),
                incrementalBatchService);
        when(instanceService.updateFinalStatusIfRunningLike(
                eq(11L), eq(JobStatus.FAILED), anyString())).thenReturn(true);

        handler.handleFinalStatus(
                11L, JobStatus.FAILED, "connect failed; password=private-test-value");

        verify(incrementalBatchService).handleJobResult(eq(11L), eq(JobStatus.FAILED),
                argThat(message -> message.contains("connect failed")
                        && !message.contains("private-test-value")));
    }

    private static JobResultHandler handler(BatchJobInstanceService instanceService) {
        return handler(
                instanceService,
                mock(ApplicationEventPublisher.class),
                mock(SeaTunnelRestClient.class));
    }

    private static JobResultHandler handler(BatchJobInstanceService instanceService,
                                            ApplicationEventPublisher publisher) {
        return handler(instanceService, publisher, mock(SeaTunnelRestClient.class));
    }

    private static JobResultHandler handler(BatchJobInstanceService instanceService,
                                            ApplicationEventPublisher publisher,
                                            SeaTunnelRestClient restClient) {
        return handler(instanceService, publisher, restClient, mock(IncrementalBatchService.class));
    }

    private static JobResultHandler handler(BatchJobInstanceService instanceService,
                                            ApplicationEventPublisher publisher,
                                            SeaTunnelRestClient restClient,
                                            IncrementalBatchService incrementalBatchService) {
        return new JobResultHandler(
                instanceService,
                mock(JobMetricsMonitor.class),
                publisher,
                incrementalBatchService,
                mock(JobLogService.class),
                restClient);
    }
}
