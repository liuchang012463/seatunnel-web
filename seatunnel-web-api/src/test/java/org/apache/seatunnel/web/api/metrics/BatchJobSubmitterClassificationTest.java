package org.apache.seatunnel.web.api.metrics;

import org.apache.seatunnel.web.engine.client.exceptions.SeaTunnelClientException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A submit failure may only be recorded as FAILED when the Engine provably never accepted the
 * request; anything else has to stay ambiguous, because retrying it could duplicate the job.
 */
class BatchJobSubmitterClassificationTest {

    @Test
    void treatsAClientErrorFromTheEngineAsNotAccepted() {
        assertFalse(BatchJobSubmitter.isSubmitOutcomeAmbiguous(
                clientException(400, new IllegalStateException("bad request"))));
        assertFalse(BatchJobSubmitter.isSubmitOutcomeAmbiguous(
                clientException(404, new IllegalStateException("no endpoint"))));
    }

    @Test
    void treatsAServerErrorFromTheEngineAsAmbiguous() {
        assertTrue(BatchJobSubmitter.isSubmitOutcomeAmbiguous(
                clientException(500, new IllegalStateException("boom"))));
        assertTrue(BatchJobSubmitter.isSubmitOutcomeAmbiguous(
                clientException(502, new IllegalStateException("bad gateway"))));
    }

    @Test
    void treatsARequestThatNeverReachedTheEngineAsNotAccepted() {
        assertFalse(BatchJobSubmitter.isSubmitOutcomeAmbiguous(new ConnectException("Connection refused")));
        assertFalse(BatchJobSubmitter.isSubmitOutcomeAmbiguous(new UnknownHostException("engine")));
        assertFalse(BatchJobSubmitter.isSubmitOutcomeAmbiguous(new NoRouteToHostException("unreachable")));
        assertFalse(BatchJobSubmitter.isSubmitOutcomeAmbiguous(
                clientException(-1, new ConnectException("Connection refused"))));
    }

    @Test
    void treatsAReadTimeoutAsAmbiguous() {
        assertTrue(BatchJobSubmitter.isSubmitOutcomeAmbiguous(new SocketTimeoutException("Read timed out")));
        assertTrue(BatchJobSubmitter.isSubmitOutcomeAmbiguous(
                clientException(-1, new SocketTimeoutException("Read timed out"))));
    }

    @Test
    void treatsAnUnknownFailureAsAmbiguous() {
        assertTrue(BatchJobSubmitter.isSubmitOutcomeAmbiguous(new IOException("reset by peer")));
        assertTrue(BatchJobSubmitter.isSubmitOutcomeAmbiguous(
                new IllegalStateException("REST submit response missing jobId")));
    }

    @Test
    void inspectsTheWholeCauseChain() {
        Exception wrapped = new RuntimeException("submit failed",
                new RuntimeException("transport", new ConnectException("Connection refused")));

        assertFalse(BatchJobSubmitter.isSubmitOutcomeAmbiguous(wrapped));
    }

    private static SeaTunnelClientException clientException(int httpStatus, Throwable cause) {
        return new SeaTunnelClientException("POST /submit-job/upload failed", httpStatus, "", cause);
    }
}
