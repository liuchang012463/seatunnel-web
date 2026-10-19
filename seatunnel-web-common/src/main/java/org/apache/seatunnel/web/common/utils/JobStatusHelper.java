package org.apache.seatunnel.web.common.utils;

import org.apache.seatunnel.web.common.enums.JobStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class JobStatusHelper {

    private JobStatusHelper() {
    }

    public static List<JobStatus> runningLikeStatuses() {
        return Arrays.asList(
                JobStatus.STARTING,
                JobStatus.INITIALIZING,
                JobStatus.CREATED,
                JobStatus.PENDING,
                JobStatus.SCHEDULED,
                JobStatus.RUNNING,
                JobStatus.FAILING,
                JobStatus.DOING_SAVEPOINT,
                JobStatus.CANCELING
        );
    }

    /**
     * Statuses that must block another submit while the current job is active, plus an ambiguous
     * prior submission: the Engine may still be running a job whose submit response never arrived,
     * so a retry would duplicate it.
     */
    public static List<JobStatus> submissionBlockingStatuses() {
        List<JobStatus> statuses = new ArrayList<>(runningLikeStatuses());
        statuses.add(JobStatus.SUBMISSION_UNKNOWN);
        return statuses;
    }

    /**
     * Whether the status must block a new submit for the same definition. This is intentionally
     * separate from {@link JobStatus#isEndState()}: an ambiguous submission is not an end state,
     * yet it still blocks retries until an operator confirms the Engine result.
     */
    public static boolean blocksSubmission(JobStatus status) {
        return status != null && submissionBlockingStatuses().contains(status);
    }
}
