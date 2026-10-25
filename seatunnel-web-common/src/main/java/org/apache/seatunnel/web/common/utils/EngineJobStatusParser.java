package org.apache.seatunnel.web.common.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.JobStatus;

import java.util.Locale;

/**
 * Maps the status the SeaTunnel Engine reports onto the local job status.
 *
 * <p>
 * The Engine vocabulary grows over time, so a status this web does not know is not evidence of a
 * final state: it must leave the instance running instead of failing a job that is still alive.
 * Every reader of an Engine status uses this parser, so the recovery path and the live watchers
 * cannot disagree about the same status.
 * </p>
 */
public final class EngineJobStatusParser {

    private EngineJobStatusParser() {
    }

    /**
     * The local status of an Engine status.
     *
     * @param engineStatus the status the Engine reported
     * @return the local status, or {@code null} when the Engine reports a status this web does
     *     not know
     */
    public static JobStatus parse(String engineStatus) {
        if (StringUtils.isBlank(engineStatus)) {
            return null;
        }

        String normalized = engineStatus.trim().toUpperCase(Locale.ROOT);

        if ("INITIALIZING".equals(normalized)
                || "CREATED".equals(normalized)
                || "PENDING".equals(normalized)
                || "SCHEDULED".equals(normalized)
                || "RUNNING".equals(normalized)
                || "FAILING".equals(normalized)
                || "DOING_SAVEPOINT".equals(normalized)
                || "CANCELING".equals(normalized)
                || "CANCELLING".equals(normalized)
                || "STOPPING".equals(normalized)) {
            return JobStatus.RUNNING;
        }

        if ("FINISHED".equals(normalized)
                || "SUCCESS".equals(normalized)
                || "SUCCEEDED".equals(normalized)
                || "DONE".equals(normalized)
                || "COMPLETED".equals(normalized)) {
            return JobStatus.FINISHED;
        }

        if ("FAILED".equals(normalized)
                || "FAILURE".equals(normalized)) {
            return JobStatus.FAILED;
        }

        if ("CANCELED".equals(normalized)
                || "CANCELLED".equals(normalized)
                || "STOPPED".equals(normalized)) {
            return JobStatus.CANCELED;
        }

        // The Engine reports these two under their own name; every other local status is a
        // web-side state the Engine never reports.
        if ("SAVEPOINT_DONE".equals(normalized)) {
            return JobStatus.SAVEPOINT_DONE;
        }
        if ("UNKNOWABLE".equals(normalized)) {
            return JobStatus.UNKNOWABLE;
        }

        return null;
    }
}
