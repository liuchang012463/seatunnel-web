package org.apache.seatunnel.web.common.utils;

import org.apache.seatunnel.web.common.enums.JobStatus;

import java.util.Arrays;
import java.util.List;

public final class JobStatusHelper {

    private JobStatusHelper() {
    }

    public static List<JobStatus> runningLikeStatuses() {
        return Arrays.asList(
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
}
