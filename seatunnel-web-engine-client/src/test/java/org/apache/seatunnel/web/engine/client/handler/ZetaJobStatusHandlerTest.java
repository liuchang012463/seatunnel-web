package org.apache.seatunnel.web.engine.client.handler;

import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
