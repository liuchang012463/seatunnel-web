package org.apache.seatunnel.web.api.metrics;

import org.apache.seatunnel.web.api.log.JobLogService;
import org.apache.seatunnel.web.api.service.BatchJobInstanceService;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobResultHandlerTest {

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
    void unknownSubmissionUsesTheConditionalStatusTransition() {
        BatchJobInstanceService instanceService = mock(BatchJobInstanceService.class);
        JobResultHandler handler = handler(instanceService);
        when(instanceService.updateStatusIfCurrent(
                eq(11L),
                eq(org.apache.seatunnel.web.common.enums.JobStatus.STARTING),
                eq(org.apache.seatunnel.web.common.enums.JobStatus.SUBMISSION_UNKNOWN),
                eq("ambiguous")))
                .thenReturn(true);

        handler.handleSubmissionUnknown(11L, "ambiguous");

        verify(instanceService).updateStatusIfCurrent(
                eq(11L),
                eq(org.apache.seatunnel.web.common.enums.JobStatus.STARTING),
                eq(org.apache.seatunnel.web.common.enums.JobStatus.SUBMISSION_UNKNOWN),
                eq("ambiguous"));
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

    private static JobResultHandler handler(BatchJobInstanceService instanceService) {
        return handler(instanceService, mock(ApplicationEventPublisher.class));
    }

    private static JobResultHandler handler(BatchJobInstanceService instanceService,
                                            ApplicationEventPublisher publisher) {
        return new JobResultHandler(
                instanceService,
                mock(JobMetricsMonitor.class),
                publisher,
                mock(IncrementalBatchService.class),
                mock(JobLogService.class));
    }
}
