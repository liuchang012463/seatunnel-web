package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.common.enums.JobStatus;

public interface IncrementalBatchService {

    /**
     * Creates or reopens exactly one fixed window. A skipped execution means
     * another window is running or no source data is safely available yet.
     */
    IncrementalBatchExecution prepare(Long jobDefinitionId);

    void bindInstance(String batchId, Long jobInstanceId);

    void markSubmitFailure(Long jobInstanceId, Throwable error);

    void markBatchFailure(String batchId, Throwable error);

    void handleJobResult(Long jobInstanceId, JobStatus status, String errorMessage);

    void removeByDefinitionId(Long jobDefinitionId);
}
