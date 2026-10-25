package org.apache.seatunnel.web.engine.client.handler;

import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.engine.client.modal.ZetaJobStatusResolveResult;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZetaJobStatusHandlerTest {
    @Test
    void failedHistoryQueryMustNotTurnAnUnreachableJobIntoAFailure() {
        SeaTunnelRestClient client = mock(SeaTunnelRestClient.class);
        when(client.runningJobs(2L)).thenReturn(List.of());
        when(client.jobInfo(2L, "101")).thenReturn(Map.of("jobId", "101"));
        when(client.finishedJobs(2L, "FINISHED")).thenThrow(new IllegalStateException("unreachable"));
        ZetaJobStatusHandler handler = new ZetaJobStatusHandler();
        ReflectionTestUtils.setField(handler, "seaTunnelRestClient", client);

        assertEquals(JobStatus.UNKNOWABLE, handler.resolve(2L, "101").getLocalStatus());
        assertFalse(handler.resolve(2L, "101").isFound());
    }

    @Test
    void blankEngineJobIdIsNotFoundInsteadOfQueryingTheEngine() {
        ZetaJobStatusHandler handler = new ZetaJobStatusHandler();

        assertFalse(handler.resolve(2L, "  ").isFound());
        assertEquals(JobStatus.FAILED, handler.resolve(2L, "  ").getLocalStatus());
    }

    @Test
    void explicitUnknowableJobInfoIsAFinishedStatus() {
        SeaTunnelRestClient client = mock(SeaTunnelRestClient.class);
        when(client.runningJobs(2L)).thenReturn(List.of());
        when(client.jobInfo(2L, "101")).thenReturn(Map.of("jobId", "101", "jobStatus", "UNKNOWABLE"));
        ZetaJobStatusHandler handler = new ZetaJobStatusHandler();
        ReflectionTestUtils.setField(handler, "seaTunnelRestClient", client);

        ZetaJobStatusResolveResult result = handler.resolve(2L, "101");

        assertTrue(result.isFound());
        assertEquals(JobStatus.UNKNOWABLE, result.getLocalStatus());
    }

    @Test
    void savepointDoneIsQueriedInTheFinishedHistory() {
        SeaTunnelRestClient client = mock(SeaTunnelRestClient.class);
        when(client.runningJobs(2L)).thenReturn(List.of());
        when(client.jobInfo(2L, "101")).thenReturn(Map.of("jobId", "101"));
        when(client.finishedJobs(2L, "FINISHED")).thenReturn(List.of());
        when(client.finishedJobs(2L, "FAILED")).thenReturn(List.of());
        when(client.finishedJobs(2L, "CANCELED")).thenReturn(List.of());
        when(client.finishedJobs(2L, "CANCELLED")).thenReturn(List.of());
        when(client.finishedJobs(2L, "SAVEPOINT_DONE"))
                .thenReturn(List.of(Map.of("jobId", "101")));
        ZetaJobStatusHandler handler = new ZetaJobStatusHandler();
        ReflectionTestUtils.setField(handler, "seaTunnelRestClient", client);

        ZetaJobStatusResolveResult result = handler.resolve(2L, "101");

        assertTrue(result.isFound());
        assertEquals(JobStatus.SAVEPOINT_DONE, result.getLocalStatus());
    }

    /**
     * An Engine status the Web does not know is not evidence of a final state. Treating it as
     * one reconciles a job that is still running into FAILED.
     */
    @Test
    void unknownEngineStatusIsNotAConfirmedTerminalState() {
        SeaTunnelRestClient client = mock(SeaTunnelRestClient.class);
        when(client.runningJobs(2L)).thenReturn(List.of());
        when(client.jobInfo(2L, "101"))
                .thenReturn(Map.of("jobId", "101", "jobStatus", "SOME_NEW_ENGINE_STATE"));
        when(client.finishedJobs(2L, "FINISHED")).thenReturn(List.of());
        when(client.finishedJobs(2L, "FAILED")).thenReturn(List.of());
        when(client.finishedJobs(2L, "CANCELED")).thenReturn(List.of());
        when(client.finishedJobs(2L, "CANCELLED")).thenReturn(List.of());
        when(client.finishedJobs(2L, "SAVEPOINT_DONE")).thenReturn(List.of());
        when(client.finishedJobs(2L, "UNKNOWABLE")).thenReturn(List.of());
        ZetaJobStatusHandler handler = new ZetaJobStatusHandler();
        ReflectionTestUtils.setField(handler, "seaTunnelRestClient", client);

        ZetaJobStatusResolveResult result = handler.resolve(2L, "101");

        assertFalse(result.isFound());
        assertFalse(result.isRunning());
    }

    /**
     * The Engine still lists the job as running, so an unrecognized status must not be read as a
     * final state either.
     */
    @Test
    void unknownEngineStatusInsideTheRunningListStaysRunning() {
        SeaTunnelRestClient client = mock(SeaTunnelRestClient.class);
        when(client.runningJobs(2L)).thenReturn(List.of(Map.of("jobId", "101", "jobStatus", "CANCELLING")));
        ZetaJobStatusHandler handler = new ZetaJobStatusHandler();
        ReflectionTestUtils.setField(handler, "seaTunnelRestClient", client);

        ZetaJobStatusResolveResult result = handler.resolve(2L, "101");

        assertTrue(result.isFound());
        assertTrue(result.isRunning());
        assertEquals(JobStatus.RUNNING, result.getLocalStatus());
    }

    @Test
    void cancellingIsMappedToRunningLikeTheStreamingWatcherDoes() {
        SeaTunnelRestClient client = mock(SeaTunnelRestClient.class);
        when(client.runningJobs(2L)).thenReturn(List.of());
        when(client.jobInfo(2L, "101"))
                .thenReturn(Map.of("jobId", "101", "jobStatus", "CANCELLING"));
        ZetaJobStatusHandler handler = new ZetaJobStatusHandler();
        ReflectionTestUtils.setField(handler, "seaTunnelRestClient", client);

        ZetaJobStatusResolveResult result = handler.resolve(2L, "101");

        assertTrue(result.isFound());
        assertTrue(result.isRunning());
        assertEquals(JobStatus.RUNNING, result.getLocalStatus());
    }
}
