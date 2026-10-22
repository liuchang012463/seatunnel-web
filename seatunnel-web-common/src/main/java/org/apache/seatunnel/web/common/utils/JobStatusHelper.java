package org.apache.seatunnel.web.common.utils;

import org.apache.seatunnel.web.common.enums.JobStatus;

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

    /** Active instances block duplicate submissions while automatic reconciliation runs. */
    public static List<JobStatus> submissionBlockingStatuses() {
        return runningLikeStatuses();
    }

    public static boolean blocksSubmission(JobStatus status) {
        return status != null && submissionBlockingStatuses().contains(status);
    }
}
