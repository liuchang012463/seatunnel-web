package org.apache.seatunnel.web.api.metrics;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.log.JobLogService;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.alarm.event.JobStatusChangedEvent;
import org.apache.seatunnel.web.api.service.BatchJobInstanceService;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.apache.seatunnel.web.api.utils.JobUtils;
import org.apache.seatunnel.web.common.enums.JobResult;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Handler responsible for processing SeaTunnel batch job execution results.
 *
 * <p>
 * It centralizes final status processing:
 * 1. Update local job instance status.
 * 2. Set end time.
 * 3. Persist error message if needed.
 * 4. Finalize and persist final metrics.
 * </p>
 */
@Component
@Slf4j
public class JobResultHandler {

    private final BatchJobInstanceService instanceService;

    private final JobMetricsMonitor jobMetricsMonitor;

    private final ApplicationEventPublisher eventPublisher;

    private final IncrementalBatchService incrementalBatchService;

    private final JobLogService jobLogService;

    private final SeaTunnelRestClient restClient;

    public JobResultHandler(BatchJobInstanceService instanceService,
                            JobMetricsMonitor jobMetricsMonitor,
                            ApplicationEventPublisher eventPublisher,
                            IncrementalBatchService incrementalBatchService,
                            JobLogService jobLogService,
                            SeaTunnelRestClient restClient) {
        this.instanceService = instanceService;
        this.jobMetricsMonitor = jobMetricsMonitor;
        this.eventPublisher = eventPublisher;
        this.incrementalBatchService = incrementalBatchService;
        this.jobLogService = jobLogService;
        this.restClient = restClient;
    }

    /**
     * Handle successful job completion.
     *
     * @param jobInstanceId job instance id
     */
    public void handleSuccess(Long jobInstanceId) {
        handleFinalStatus(jobInstanceId, JobStatus.FINISHED, null);
        log.info("Job completed successfully. instanceId={}", jobInstanceId);
    }

    /**
     * Handle job failure caused by exception.
     *
     * @param jobInstanceId job instance id
     * @param error         exception
     */
    public void handleFailure(Long jobInstanceId, Throwable error) {
        String message = buildThrowableMessage(error);

        handleFinalStatus(jobInstanceId, JobStatus.FAILED, message);

        log.error("Job failed. instanceId={}, error={}", jobInstanceId, message, error);
    }

    /**
     * Record an ambiguous submit result without claiming that Engine rejected the job.
     * Retrying such an instance automatically could create a duplicate Engine job.
     *
     * @param jobInstanceId job instance id
     * @param message       operator-facing explanation
     */
    public void handleSubmissionUnknown(Long jobInstanceId, String message) {
        if (jobInstanceId == null || jobInstanceId <= 0) {
            log.warn("Skip recording unknown submission because instanceId is invalid, instanceId={}",
                    jobInstanceId);
            return;
        }

        // Only a STARTING instance can become ambiguous. If the submit response won the race and
        // already recorded an Engine id, overwriting it would lose the real job reference.
        boolean updated = instanceService.updateStatusIfCurrent(
                jobInstanceId,
                JobStatus.STARTING,
                JobStatus.SUBMISSION_UNKNOWN,
                message);
        if (!updated) {
            log.warn("Skip recording unknown submission because the instance left STARTING, "
                    + "instanceId={}", jobInstanceId);
            return;
        }

        publishStatusChanged(jobInstanceId, JobStatus.SUBMISSION_UNKNOWN, message);

        try {
            incrementalBatchService.handleJobResult(
                    jobInstanceId, JobStatus.SUBMISSION_UNKNOWN, message);
        } catch (Exception e) {
            log.error("Update incremental batch after unknown submission failed, instanceId={}",
                    jobInstanceId, e);
        }

        log.warn("Job submission outcome is unknown. instanceId={}, message={}", jobInstanceId, message);
    }

    /**
     * Handle job failure based on engine job result.
     *
     * @param jobInstanceId job instance id
     * @param jobResult     engine result
     */
    public void handleFailure(Long jobInstanceId, JobResult jobResult) {
        JobStatus engineStatus = jobResult == null ? JobStatus.FAILED : jobResult.getStatus();
        String message = jobResult == null ? "Unknown error" : jobResult.getError();

        handleFinalStatus(jobInstanceId, engineStatus, message);

        log.error(
                "Job failed. instanceId={}, status={}, error={}",
                jobInstanceId,
                engineStatus,
                message
        );
    }

    /**
     * Handle final status in normal watcher mode.
     *
     * <p>
     * This method depends on JobMetricsMonitor internal monitoringJobs map.
     * It is suitable for normal submit -> monitor -> watcher flow.
     * </p>
     *
     * @param jobInstanceId job instance id
     * @param engineStatus  final status returned by engine
     * @param errorMessage  error message
     */
    public void handleFinalStatus(Long jobInstanceId,
                                  JobStatus engineStatus,
                                  String errorMessage) {
        if (jobInstanceId == null || jobInstanceId <= 0) {
            log.warn("Skip handling final status because jobInstanceId is invalid, jobInstanceId={}",
                    jobInstanceId);
            return;
        }

        JobStatus localStatus = normalizeFinalStatus(engineStatus);
        String metricsStatus = resolveMetricsStatus(engineStatus, localStatus);

        if (!updateStatus(jobInstanceId, localStatus, errorMessage)) {
            log.warn("Ignore late final status because the instance is no longer running-like, "
                    + "instanceId={}, engineStatus={}, localStatus={}",
                    jobInstanceId, engineStatus, localStatus);
            return;
        }

        try {
            incrementalBatchService.handleJobResult(jobInstanceId, localStatus, errorMessage);
        } catch (Exception e) {
            log.error("Finalize incremental batch state failed, instanceId={}", jobInstanceId, e);
        }

        try {
            jobLogService.persistEngineLog(jobInstanceId, org.apache.seatunnel.web.common.enums.JobMode.BATCH);
        } catch (Exception e) {
            log.warn("Persist complete batch Engine log failed, instanceId={}", jobInstanceId, e);
        }

        try {
            jobMetricsMonitor.finalizeAndPersist(jobInstanceId, metricsStatus);
        } catch (Exception e) {
            log.warn(
                    "Finalize batch metrics failed, instanceId={}, metricsStatus={}",
                    jobInstanceId,
                    metricsStatus,
                    e
            );
        }
    }

    /**
     * Handle final status in recovery mode.
     *
     * <p>
     * This method does not depend on JobMetricsMonitor internal monitoringJobs map.
     * It is mainly used after SeaTunnel Web restart. Recovery logic can rebuild
     * JobRuntimeContext from database instance data and call this method directly.
     * </p>
     *
     * @param context      runtime context rebuilt from persisted instance
     * @param engineStatus final status returned by engine
     * @param errorMessage error message
     */
    public void handleFinalStatus(JobRuntimeContext context,
                                  JobStatus engineStatus,
                                  String errorMessage) {
        if (context == null || context.getInstanceId() == null || context.getInstanceId() <= 0) {
            log.warn("Skip handling final status because runtime context is invalid, context={}",
                    context);
            return;
        }

        Long jobInstanceId = context.getInstanceId();

        JobStatus localStatus = normalizeFinalStatus(engineStatus);
        String metricsStatus = resolveMetricsStatus(engineStatus, localStatus);

        if (!updateStatus(jobInstanceId, localStatus, errorMessage)) {
            log.warn("Ignore late final status because the instance is no longer running-like, "
                    + "instanceId={}, engineStatus={}, localStatus={}",
                    jobInstanceId, engineStatus, localStatus);
            return;
        }

        try {
            incrementalBatchService.handleJobResult(jobInstanceId, localStatus, errorMessage);
        } catch (Exception e) {
            log.error("Finalize incremental batch state in recovery failed, instanceId={}", jobInstanceId, e);
        }

        try {
            jobLogService.persistEngineLog(jobInstanceId, org.apache.seatunnel.web.common.enums.JobMode.BATCH);
        } catch (Exception e) {
            log.warn("Persist complete batch Engine log in recovery failed, instanceId={}", jobInstanceId, e);
        }

        try {
            jobMetricsMonitor.finalizeAndPersist(context, metricsStatus);
        } catch (Exception e) {
            log.warn(
                    "Finalize batch metrics with context failed, instanceId={}, engineId={}, metricsStatus={}",
                    jobInstanceId,
                    context.getEngineId(),
                    metricsStatus,
                    e
            );
        }
    }

    /**
     * Update engine job id after successful submission.
     *
     * @param instanceId job instance id
     * @param engineId   engine job id
     */
    public void updateEngineId(Long instanceId, String engineId) {
        if (instanceId == null || instanceId <= 0) {
            log.warn("Skip updating engineId because instanceId is invalid, instanceId={}", instanceId);
            return;
        }

        Date now = new Date();
        boolean updated = instanceService.updateEngineIdIfStarting(instanceId, engineId, now);
        if (!updated) {
            /*
             * The instance left STARTING while the submit response was in flight: either the
             * recovery scheduler or an operator already resolved it as an ambiguous submission.
             * Overwriting that decision would revive a terminal instance and could allow a
             * duplicate Engine job, so the late success is reported as a conflict instead.
             */
            log.error("Late submission success ignored because the instance is no longer STARTING, "
                    + "instanceId={}, engineId={}", instanceId, engineId);
            cancelOrphanedEngineJob(instanceId, engineId);
            return;
        }

        log.info("Job submitted. instanceId={}, engineId={}", instanceId, engineId);
    }

    /**
     * If the Web already recorded a terminal result (for example an operator resolved an
     * ambiguous submission as FAILED), a late Engine id must not leave an untracked job running.
     * A still-open instance is left alone: the operator or recovery path owns that decision.
     */
    private void cancelOrphanedEngineJob(Long instanceId, String engineId) {
        try {
            JobInstanceVO instance = instanceService.selectById(instanceId);
            if (instance == null || instance.getClientId() == null
                    || StringUtils.isBlank(instance.getJobStatus())) {
                log.warn("Cannot cancel late Engine job without an instance client, "
                        + "instanceId={}, engineId={}", instanceId, engineId);
                return;
            }
            JobStatus localStatus = JobStatus.fromString(instance.getJobStatus());
            if (!localStatus.isEndState() || JobStatus.FINISHED.equals(localStatus)) {
                return;
            }

            restClient.stopJob(instance.getClientId(), engineId, false);
            log.warn("Cancelled orphaned Engine job for a terminal instance, "
                    + "instanceId={}, engineId={}, status={}",
                    instanceId, engineId, localStatus);
        } catch (Exception error) {
            log.warn("Could not cancel late Engine job, instanceId={}, engineId={}",
                    instanceId, engineId, error);
        }
    }

    private boolean updateStatus(Long jobInstanceId,
                                 JobStatus status,
                                 String errorMessage) {
        boolean updated = instanceService.updateFinalStatusIfRunningLike(
                jobInstanceId, status, normalizeErrorMessage(status, errorMessage));
        if (!updated) {
            return false;
        }

        publishStatusChanged(jobInstanceId, status, errorMessage);

        log.info(
                "Batch job instance final status updated, instanceId={}, status={}, errorMessage={}",
                jobInstanceId,
                status,
                errorMessage
        );
        return true;
    }

    private String normalizeErrorMessage(JobStatus status, String errorMessage) {
        /*
         * FINISHED is normal success, do not write error_message.
         * FAILED / CANCELED should keep the reason as much as possible.
         */
        return JobStatus.FINISHED.equals(status) ? null : normalizeErrorMessage(errorMessage);
    }

    private void publishStatusChanged(Long jobInstanceId, JobStatus status, String errorMessage) {
        // Publish a status-change event so the alarm sub-system (and any other
        // listener) can react. oldStatus / engineJobId are unknown here; the
        // alarm engine enriches them via JobInstanceLookup.
        try {
            eventPublisher.publishEvent(new JobStatusChangedEvent(
                    this, jobInstanceId, null, status, null,
                    errorMessage, null));
        } catch (Exception e) {
            log.warn("Publish job status change event failed, instanceId={}", jobInstanceId, e);
        }
    }

    private JobStatus normalizeFinalStatus(JobStatus engineStatus) {
        if (engineStatus == null) {
            return JobStatus.FAILED;
        }

        if (JobStatus.FINISHED.equals(engineStatus)) {
            return JobStatus.FINISHED;
        }

        if (JobStatus.CANCELED.equals(engineStatus)) {
            return JobStatus.CANCELED;
        }

        return JobStatus.FAILED;
    }

    private String resolveMetricsStatus(JobStatus engineStatus, JobStatus localStatus) {
        if (engineStatus != null) {
            return engineStatus.name();
        }

        if (localStatus != null) {
            return localStatus.name();
        }

        return JobStatus.FAILED.name();
    }

    private String buildThrowableMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }

        String message = error.getMessage();
        if (StringUtils.isBlank(message)) {
            return error.getClass().getSimpleName();
        }

        return JobUtils.getJobInstanceErrorMessage(message);
    }

    private String normalizeErrorMessage(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            return "Unknown error";
        }

        return JobUtils.getJobInstanceErrorMessage(errorMessage);
    }
}
