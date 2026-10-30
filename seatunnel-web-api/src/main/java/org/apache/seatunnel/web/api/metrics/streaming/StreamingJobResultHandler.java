package org.apache.seatunnel.web.api.metrics.streaming;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.log.JobLogService;
import org.apache.seatunnel.web.api.service.StreamingJobInstanceService;
import org.apache.seatunnel.web.api.utils.HoconSensitiveMaskUtil;
import org.apache.seatunnel.web.api.utils.JobUtils;
import org.apache.seatunnel.web.api.utils.RedactedErrors;
import org.apache.seatunnel.web.common.enums.JobResult;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.utils.JobStatusHelper;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Slf4j
public class StreamingJobResultHandler {

    private final StreamingJobInstanceService streamingJobInstanceService;
    private final StreamingJobMetricsMonitor streamingJobMetricsMonitor;
    private final JobLogService jobLogService;

    public StreamingJobResultHandler(StreamingJobInstanceService streamingJobInstanceService,
                                     StreamingJobMetricsMonitor streamingJobMetricsMonitor,
                                     JobLogService jobLogService) {
        this.streamingJobInstanceService = streamingJobInstanceService;
        this.streamingJobMetricsMonitor = streamingJobMetricsMonitor;
        this.jobLogService = jobLogService;
    }

    public void handleSuccess(Long jobInstanceId) {
        updateStatus(jobInstanceId, JobStatus.FINISHED, null);
        log.info("Streaming job completed successfully. instanceId={}", jobInstanceId);
    }

    public void handleCanceled(Long jobInstanceId, String message) {
        String finalMessage = message == null || message.trim().isEmpty()
                ? "Streaming job was canceled."
                // The engine error text can carry connector credentials, so it is masked before
                // it reaches the instance row.
                : RedactedErrors.mask(message);

        updateStatus(jobInstanceId, JobStatus.CANCELED, finalMessage);
        log.info("Streaming job canceled. instanceId={}, message={}", jobInstanceId, finalMessage);
    }

    /** Records an in-flight cancellation without finalizing metrics before Engine state is known. */
    public boolean handleCanceling(Long jobInstanceId, String engineJobId, String message) {
        boolean updated = streamingJobInstanceService.markCancelingIfStarting(
                jobInstanceId, engineJobId, RedactedErrors.mask(message));
        if (updated) {
            log.info("Streaming job is canceling while submission is in flight. instanceId={}", jobInstanceId);
        }
        return updated;
    }

    /** Returns true when a queued/preflight submission should not send its Engine request. */
    public boolean skipSubmissionIfNotStarting(Long jobInstanceId, String engineJobId) {
        JobInstanceVO current = streamingJobInstanceService.selectById(jobInstanceId);
        if (current != null && JobStatus.STARTING.name().equalsIgnoreCase(current.getJobStatus())
                && engineJobId != null && engineJobId.equals(current.getEngineJobId())) {
            return false;
        }
        if (isCancellationRequested(current, engineJobId)) {
            handleCanceledIfRunningLike(jobInstanceId, "Streaming job was canceled before Engine submission.");
        }
        return true;
    }

    public boolean isCancellationRequested(Long jobInstanceId, String engineJobId) {
        return isCancellationRequested(streamingJobInstanceService.selectById(jobInstanceId), engineJobId);
    }

    public void handleFailure(Long jobInstanceId, Throwable error) {
        if (shouldSkipFailureOverwrite(jobInstanceId)) {
            log.info("Skip failure overwrite because streaming job is already finished. instanceId={}", jobInstanceId);
            return;
        }

        String message = error == null
                ? "Unknown error"
                : JobUtils.getJobInstanceErrorMessage(
                        HoconSensitiveMaskUtil.maskSensitiveText(error.getMessage()));

        updateStatus(jobInstanceId, JobStatus.FAILED, message);
        // The engine error text can carry connector credentials, so the log carries the masked
        // message and the stack frames of the failure instead of the original exception.
        log.error("Streaming job failed. instanceId={}, errorType={}, error={}",
                jobInstanceId,
                RedactedErrors.typeOf(error),
                message,
                RedactedErrors.redacted(error, message));
    }

    public void handleFailure(Long jobInstanceId, JobResult jobResult) {
        JobStatus realStatus = jobResult == null ? null : jobResult.getStatus();

        if (realStatus == JobStatus.FINISHED) {
            handleSuccess(jobInstanceId);
            return;
        }

        if (jobResult != null && jobResult.getStatus() == JobStatus.CANCELED) {
            handleCanceled(jobInstanceId, jobResult.getError());
            return;
        }

        if (shouldSkipFailureOverwrite(jobInstanceId)) {
            log.info("Skip failure overwrite because streaming job is already finished. instanceId={}, engineStatus={}",
                    jobInstanceId, realStatus);
            return;
        }

        String message = jobResult != null
                ? HoconSensitiveMaskUtil.maskSensitiveText(jobResult.getError())
                : "Unknown error";

        updateStatus(jobInstanceId, JobStatus.FAILED, message);

        log.error(
                "Streaming job failed. instanceId={}, status={}, error={}",
                jobInstanceId,
                realStatus,
                message
        );
    }

    public boolean updateEngineId(Long instanceId, String engineId) {
        Date now = new Date();
        boolean updated = streamingJobInstanceService.updateEngineIdIfStarting(instanceId, engineId, now);
        if (!updated) {
            log.error("Late streaming submission success ignored because the instance is no longer STARTING, "
                    + "instanceId={}, engineId={}", instanceId, engineId);
            return false;
        }

        log.info("Streaming job submitted. instanceId={}, engineId={}", instanceId, engineId);
        return true;
    }

    public boolean handleFinalStatusIfStarting(Long instanceId,
                                               String expectedEngineJobId,
                                               Date expectedLastActivity,
                                               JobStatus status,
                                               String errorMessage) {
        if (instanceId == null || instanceId <= 0 || status == null) {
            return false;
        }
        String safeMessage = JobStatus.FINISHED.equals(status) ? null : RedactedErrors.mask(errorMessage);
        boolean updated = streamingJobInstanceService.updateFinalStatusIfStarting(
                instanceId, expectedEngineJobId, expectedLastActivity, status, safeMessage);
        if (!updated) {
            log.info("Skip recovered streaming final status because STARTING snapshot changed, instanceId={}, status={}",
                    instanceId, status);
            return false;
        }
        persistFinalResult(instanceId, status);
        return true;
    }

    public boolean isEngineJobTracked(Long instanceId, String engineId) {
        JobInstanceVO current = streamingJobInstanceService.selectById(instanceId);
        if (current == null
                || !engineId.equals(current.getEngineJobId())
                || current.getJobStatus() == null) {
            return false;
        }
        if (JobStatus.FINISHED.name().equalsIgnoreCase(current.getJobStatus())) {
            return true;
        }
        try {
            return JobStatusHelper.blocksSubmission(JobStatus.fromString(current.getJobStatus()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isCancellationRequested(JobInstanceVO current, String engineJobId) {
        return current != null
                && JobStatus.CANCELING.name().equalsIgnoreCase(current.getJobStatus())
                && engineJobId != null
                && engineJobId.equals(current.getEngineJobId());
    }

    private boolean handleCanceledIfRunningLike(Long jobInstanceId, String message) {
        boolean updated = streamingJobInstanceService.updateFinalStatusIfRunningLike(
                jobInstanceId, JobStatus.CANCELED, RedactedErrors.mask(message));
        if (updated) {
            persistFinalResult(jobInstanceId, JobStatus.CANCELED);
        }
        return updated;
    }

    private void updateStatus(Long jobInstanceId, JobStatus status, String errorMessage) {
        StreamingJobInstance po = new StreamingJobInstance();
        po.setId(jobInstanceId);
        po.setJobStatus(status);
        po.setEndTime(new Date());
        po.setErrorMessage(errorMessage);

        streamingJobInstanceService.updateById(po);

        persistFinalResult(jobInstanceId, status);
    }

    private void persistFinalResult(Long jobInstanceId, JobStatus status) {
        try {
            jobLogService.persistEngineLog(jobInstanceId, org.apache.seatunnel.web.common.enums.JobMode.STREAMING);
        } catch (Exception e) {
            log.warn("Persist complete streaming Engine log failed, instanceId={}", jobInstanceId, e);
        }

        streamingJobMetricsMonitor.finalizeAndPersist(jobInstanceId, status.name());
    }

    private boolean shouldSkipFailureOverwrite(Long jobInstanceId) {
        JobInstanceVO current = streamingJobInstanceService.selectById(jobInstanceId);
        if (current == null || current.getJobStatus() == null) {
            return false;
        }

        String currentStatus = current.getJobStatus();

        return "FINISHED".equalsIgnoreCase(currentStatus)
                || "CANCELED".equalsIgnoreCase(currentStatus)
                || "CANCELLED".equalsIgnoreCase(currentStatus)
                || "STOPPED".equalsIgnoreCase(currentStatus);
    }
}
