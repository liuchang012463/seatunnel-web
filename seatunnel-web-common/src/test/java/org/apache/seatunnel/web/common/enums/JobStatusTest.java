package org.apache.seatunnel.web.common.enums;

import org.apache.seatunnel.web.common.utils.JobStatusHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStatusTest {

    @Test
    void runningStatusesBlockRetriesButAreNotTerminal() {
        assertFalse(JobStatus.STARTING.isEndState());
        assertFalse(JobStatus.RUNNING.isEndState());
        assertTrue(JobStatusHelper.blocksSubmission(JobStatus.STARTING));
        assertTrue(JobStatusHelper.blocksSubmission(JobStatus.RUNNING));
    }

    @Test
    void confirmedResultsAreTerminalAndDoNotBlockRetries() {
        assertTrue(JobStatus.FINISHED.isEndState());
        assertTrue(JobStatus.FAILED.isEndState());
        assertTrue(JobStatus.CANCELED.isEndState());
        assertFalse(JobStatusHelper.blocksSubmission(JobStatus.FINISHED));
        assertFalse(JobStatusHelper.blocksSubmission(JobStatus.FAILED));
        assertFalse(JobStatusHelper.blocksSubmission(null));
    }
}
