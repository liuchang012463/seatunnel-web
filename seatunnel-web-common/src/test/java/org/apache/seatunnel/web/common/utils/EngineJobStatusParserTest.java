package org.apache.seatunnel.web.common.utils;

import org.apache.seatunnel.web.common.enums.JobStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EngineJobStatusParserTest {

    @Test
    void mapsTheRunningStatesOfTheEngine() {
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("RUNNING"));
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("INITIALIZING"));
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("CREATED"));
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("PENDING"));
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("SCHEDULED"));
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("FAILING"));
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("DOING_SAVEPOINT"));
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("CANCELING"));
        // The spelling the streaming watcher already knew.
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("CANCELLING"));
        assertEquals(JobStatus.RUNNING, EngineJobStatusParser.parse("STOPPING"));
    }

    @Test
    void mapsTheFinalStatesOfTheEngine() {
        assertEquals(JobStatus.FINISHED, EngineJobStatusParser.parse("FINISHED"));
        assertEquals(JobStatus.FINISHED, EngineJobStatusParser.parse("SUCCESS"));
        assertEquals(JobStatus.FINISHED, EngineJobStatusParser.parse("SUCCEEDED"));
        assertEquals(JobStatus.FINISHED, EngineJobStatusParser.parse("DONE"));
        assertEquals(JobStatus.FINISHED, EngineJobStatusParser.parse("COMPLETED"));
        assertEquals(JobStatus.FAILED, EngineJobStatusParser.parse("FAILED"));
        assertEquals(JobStatus.FAILED, EngineJobStatusParser.parse("FAILURE"));
        assertEquals(JobStatus.CANCELED, EngineJobStatusParser.parse("CANCELED"));
        assertEquals(JobStatus.CANCELED, EngineJobStatusParser.parse("CANCELLED"));
        assertEquals(JobStatus.CANCELED, EngineJobStatusParser.parse("STOPPED"));
        assertEquals(JobStatus.SAVEPOINT_DONE, EngineJobStatusParser.parse("SAVEPOINT_DONE"));
        assertEquals(JobStatus.UNKNOWABLE, EngineJobStatusParser.parse("UNKNOWABLE"));
    }

    /**
     * A status the web does not know is not evidence of a final state: it must not be mapped to
     * UNKNOWABLE, because that state ends the instance.
     */
    @Test
    void anUnknownStatusHasNoLocalStatus() {
        assertNull(EngineJobStatusParser.parse("SOME_NEW_ENGINE_STATE"));
        assertNull(EngineJobStatusParser.parse("STARTING"));
        assertNull(EngineJobStatusParser.parse(""));
        assertNull(EngineJobStatusParser.parse(null));
    }
}
