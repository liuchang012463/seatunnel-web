package org.apache.seatunnel.web.api.client.scheduler;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metrics.JobResultHandler;
import org.apache.seatunnel.web.api.metrics.JobRuntimeContext;
import org.apache.seatunnel.web.api.metrics.streaming.StreamingJobResultHandler;
import org.apache.seatunnel.web.api.utils.HoconSensitiveMaskUtil;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.engine.client.handler.ZetaJobStatusHandler;
import org.apache.seatunnel.web.engine.client.modal.ZetaJobStatusResolveResult;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * Recover local job instance status after SeaTunnel Web restart.
 *
 * <p>
 * When SeaTunnel Web is stopped or restarted, in-memory watchers are lost.
 * Some local job instances may remain RUNNING even though the corresponding
 * Zeta job has already finished.
 * </p>
 */
@Slf4j
@Component
public class JobInstanceRecoveryScheduler {

    private static final String JOB_TYPE_BATCH = "BATCH";
    /**
     * A submission that produced no Engine job id within this window counts as stuck. It is far
     * above the submit read timeout, so an in-flight submission is never misread as stuck.
     */
    private static final long SUBMISSION_GRACE_MS = 10 * 60 * 1000L;

    @Resource
    private JobInstanceDao jobInstanceDao;

    @Resource
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @Resource
    private ZetaJobStatusHandler zetaJobStatusHandler;

    @Resource
    private SeaTunnelRestClient seaTunnelRestClient;

    @Resource
    private JobResultHandler jobResultHandler;

    @Resource
    private StreamingJobResultHandler streamingJobResultHandler;

    @Value("${seatunnel.job.recovery.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!enabled) {
            return;
        }

        log.info("Start recovering running job instances after SeaTunnel Web startup");

        recoverBatchInstances();
        recoverStreamingInstances();

        log.info("Recover running job instances after SeaTunnel Web startup finished");
    }

    @Scheduled(
            initialDelayString = "${seatunnel.job.recovery.initial-delay-ms:60000}",
            fixedDelayString = "${seatunnel.job.recovery.fixed-delay-ms:60000}"
    )
    public void recoverPeriodically() {
        if (!enabled) {
            return;
        }

        recoverBatchInstances();
        recoverStreamingInstances();
    }

    private void recoverBatchInstances() {
        List<JobInstance> instances = jobInstanceDao.listRunningLikeInstances();
        if (instances == null || instances.isEmpty()) {
            return;
        }

        log.info("Start recovering batch running-like instances, count={}", instances.size());

        for (JobInstance instance : instances) {
            try {
                recoverBatchInstance(instance);
            } catch (Exception e) {
                log.warn(
                        "Recover batch job instance failed, instanceId={}, engineJobId={}",
                        instance == null ? null : instance.getId(),
                        instance == null ? null : instance.getEngineJobId(),
                        e
                );
            }
        }
    }

    private boolean isStaleSubmission(JobInstance instance) {
        Date lastActivity = lastActivity(instance);
        return lastActivity != null
                && lastActivity.getTime() < System.currentTimeMillis() - SUBMISSION_GRACE_MS;
    }

    private boolean isStaleSubmission(StreamingJobInstance instance) {
        Date lastActivity = lastActivity(instance);
        return lastActivity != null
                && lastActivity.getTime() < System.currentTimeMillis() - SUBMISSION_GRACE_MS;
    }

    private static Date lastActivity(JobInstance instance) {
        return instance.getUpdateTime() != null ? instance.getUpdateTime() : instance.getCreateTime();
    }

    private static Date lastActivity(StreamingJobInstance instance) {
        return instance.getUpdateTime() != null ? instance.getUpdateTime() : instance.getCreateTime();
    }

    private void recoverStreamingInstances() {
        List<StreamingJobInstance> instances = streamingJobInstanceDao.listRunningLikeInstances();
        if (instances == null || instances.isEmpty()) {
            return;
        }

        log.info("Start recovering streaming running-like instances, count={}", instances.size());

        for (StreamingJobInstance instance : instances) {
            try {
                recoverStreamingInstance(instance);
            } catch (Exception e) {
                log.warn(
                        "Recover streaming job instance failed, instanceId={}, engineJobId={}",
                        instance == null ? null : instance.getId(),
                        instance == null ? null : instance.getEngineJobId(),
                        e
                );
            }
        }
    }

    private void recoverBatchInstance(JobInstance instance) {
        if (instance == null || instance.getId() == null) {
            return;
        }

        if (isSubmissionInFlight(instance.getJobStatus())) {
            // Active async submissions, including a cancellation requested during upload,
            // refresh update_time. Wait for the lease to expire before resolving the reserved id.
            if (!isStaleSubmission(instance)) {
                return;
            }
            if (instance.getEngineJobId() == null || instance.getEngineJobId().isBlank()) {
                JobRuntimeContext context = buildBatchRuntimeContext(instance);
                if (JobStatus.CANCELING.equals(instance.getJobStatus())) {
                    jobResultHandler.handleFinalStatus(
                            context, JobStatus.CANCELED, "任务取消已确认，未保存 Engine 作业 ID。");
                } else {
                    jobResultHandler.handleFinalStatusIfStarting(
                            context,
                            JobStatus.FAILED,
                            "任务提交中断，未保存 Engine 作业 ID，已自动标记失败。",
                            instance.getEngineJobId(),
                            lastActivity(instance));
                }
                return;
            }
        }

        ZetaJobStatusResolveResult result = zetaJobStatusHandler.resolve(
                instance.getClientId(),
                instance.getEngineJobId()
        );

        if (result != null && result.isRunning()
                && JobStatus.STARTING.equals(instance.getJobStatus())) {
            jobResultHandler.updateEngineId(instance.getId(), instance.getEngineJobId());
        }

        if (result != null && result.isRunning()
                && JobStatus.CANCELING.equals(instance.getJobStatus())) {
            requestStopForCanceling(instance.getId(), instance.getClientId(), instance.getEngineJobId());
            return;
        }

        if (shouldSkipRecovery(instance.getId(), instance.getEngineJobId(), result)) {
            return;
        }

        JobStatus targetStatus = adjustNotFoundStatus(instance.getJobStatus(), result);

        String errorMessage = buildRecoveryMessage(
                instance.getId(),
                instance.getEngineJobId(),
                result
        );

        log.warn(
                "Recover batch job instance final status, instanceId={}, engineJobId={}, targetStatus={}, engineStatus={}, message={}",
                instance.getId(),
                instance.getEngineJobId(),
                targetStatus,
                result.getEngineStatus(),
                result.getMessage()
        );

        JobRuntimeContext context = buildBatchRuntimeContext(instance);

        /*
         * Important:
         * Recovery must use context mode.
         *
         * Because SeaTunnel Web has restarted, JobMetricsMonitor.monitoringJobs is empty.
         * If we only pass instanceId, metrics finalization cannot find runtime context.
         */
        if (JobStatus.STARTING.equals(instance.getJobStatus())) {
            jobResultHandler.handleFinalStatusIfStarting(
                    context,
                    targetStatus,
                    errorMessage,
                    instance.getEngineJobId(),
                    lastActivity(instance));
        } else {
            jobResultHandler.handleFinalStatus(context, targetStatus, errorMessage);
        }
    }

    private void recoverStreamingInstance(StreamingJobInstance instance) {
        if (instance == null || instance.getId() == null) {
            return;
        }

        if (isSubmissionInFlight(instance.getJobStatus())) {
            if (!isStaleSubmission(instance)) {
                return;
            }
            if (instance.getEngineJobId() == null || instance.getEngineJobId().isBlank()) {
                if (JobStatus.CANCELING.equals(instance.getJobStatus())) {
                    streamingJobInstanceDao.updateFinalStatusIfRunningLike(
                            instance.getId(), JobStatus.CANCELED, "任务取消已确认，未保存 Engine 作业 ID。");
                } else {
                    streamingJobResultHandler.handleFinalStatusIfStarting(
                            instance.getId(),
                            instance.getEngineJobId(),
                            lastActivity(instance),
                            JobStatus.FAILED,
                            "任务提交中断，未保存 Engine 作业 ID，已自动标记失败。");
                }
                return;
            }
        }

        ZetaJobStatusResolveResult result = zetaJobStatusHandler.resolve(
                instance.getClientId(),
                instance.getEngineJobId()
        );

        if (result != null && result.isRunning()
                && JobStatus.STARTING.equals(instance.getJobStatus())) {
            streamingJobInstanceDao.updateEngineIdIfStarting(
                    instance.getId(), instance.getEngineJobId(), new Date());
        }

        if (result != null && result.isRunning()
                && JobStatus.CANCELING.equals(instance.getJobStatus())) {
            requestStopForCanceling(instance.getId(), instance.getClientId(), instance.getEngineJobId());
            return;
        }

        if (shouldSkipRecovery(instance.getId(), instance.getEngineJobId(), result)) {
            return;
        }

        JobStatus targetStatus = adjustNotFoundStatus(instance.getJobStatus(), result);
        if (JobStatus.UNKNOWABLE.equals(targetStatus)) {
            // The batch path normalizes a confirmed UNKNOWABLE to FAILED. Streaming must agree,
            // otherwise the row keeps a status no terminal mapping (UI, OpenLineage) understands.
            targetStatus = JobStatus.FAILED;
        }

        String errorMessage = buildRecoveryMessage(
                instance.getId(),
                instance.getEngineJobId(),
                result
        );

        log.warn(
                "Recover streaming job instance final status, instanceId={}, engineJobId={}, targetStatus={}, engineStatus={}, message={}",
                instance.getId(),
                instance.getEngineJobId(),
                targetStatus,
                result.getEngineStatus(),
                result.getMessage()
        );

        // The status is written only while the instance is still running: a job the operator
        // stopped in the meantime keeps its own status.
        boolean updated = JobStatus.STARTING.equals(instance.getJobStatus())
                ? streamingJobResultHandler.handleFinalStatusIfStarting(
                        instance.getId(),
                        instance.getEngineJobId(),
                        lastActivity(instance),
                        targetStatus,
                        errorMessage)
                : streamingJobInstanceDao.updateFinalStatusIfRunningLike(
                        instance.getId(), targetStatus, errorMessage);
        if (!updated) {
            log.info(
                    "Skip streaming recovery because the instance is no longer running, instanceId={}, targetStatus={}",
                    instance.getId(),
                    targetStatus
            );
        }
    }

    private JobRuntimeContext buildBatchRuntimeContext(JobInstance instance) {
        JobRuntimeContext context = new JobRuntimeContext();

        context.setInstanceId(instance.getId());
        context.setJobDefinitionId(instance.getJobDefinitionId());
        context.setClientId(instance.getClientId());
        context.setEngineId(instance.getEngineJobId());
        context.setJobType(JOB_TYPE_BATCH);

        /*
         * Recovery 场景下 configFile 可以为空。
         * finalize metrics 主要依赖 instanceId、definitionId、clientId、engineId。
         */
        context.setConfigFile(null);

        return context;
    }

    private boolean shouldSkipRecovery(Long instanceId,
                                       String engineJobId,
                                       ZetaJobStatusResolveResult result) {
        if (result == null) {
            log.warn(
                    "Skip recovering job instance because Zeta resolve result is null, instanceId={}, engineJobId={}",
                    instanceId,
                    engineJobId
            );
            return true;
        }

        if (result.isRunning()) {
            log.debug(
                    "Skip recovering job instance because Zeta job is still running, instanceId={}, engineJobId={}, engineStatus={}",
                    instanceId,
                    engineJobId,
                    result.getEngineStatus()
            );
            return true;
        }

        JobStatus localStatus = result.getLocalStatus();

        if (localStatus == null) {
            log.warn(
                    "Skip recovering job instance because local status is null, instanceId={}, engineJobId={}, message={}",
                    instanceId,
                    engineJobId,
                    result.getMessage()
            );
            return true;
        }

        // An unresolved status (the Engine was unreachable) is retried next round. A status the
        // Engine explicitly reports as UNKNOWABLE is final and must be reconciled, otherwise the
        // instance blocks its definition forever.
        if (JobStatus.UNKNOWABLE.equals(localStatus) && !result.isFound()) {
            log.warn(
                    "Skip recovering job instance because Zeta status is unknown, instanceId={}, engineJobId={}, message={}",
                    instanceId,
                    engineJobId,
                    result.getMessage()
            );
            return true;
        }

        if (!localStatus.isEndState()) {
            log.debug(
                    "Skip recovering job instance because target status is not end state, instanceId={}, engineJobId={}, targetStatus={}",
                    instanceId,
                    engineJobId,
                    localStatus
            );
            return true;
        }

        return false;
    }

    /**
     * A job the Engine no longer knows did not necessarily fail: a canceled job and a
     * savepoint-stopped job are cleaned up by the Engine too. Map those two local intents to
     * their real terminal state instead of writing FAILED.
     */
    private static JobStatus adjustNotFoundStatus(
            JobStatus currentStatus, ZetaJobStatusResolveResult result) {
        JobStatus targetStatus = result.getLocalStatus();
        if (!"NOT_FOUND".equals(result.getEngineStatus())) {
            return targetStatus;
        }
        if (JobStatus.CANCELING.equals(currentStatus)) {
            return JobStatus.CANCELED;
        }
        if (JobStatus.DOING_SAVEPOINT.equals(currentStatus)) {
            return JobStatus.SAVEPOINT_DONE;
        }
        return targetStatus;
    }

    private static boolean isSubmissionInFlight(JobStatus status) {
        return JobStatus.STARTING.equals(status) || JobStatus.CANCELING.equals(status);
    }

    private void requestStopForCanceling(Long instanceId, Long clientId, String engineJobId) {
        try {
            seaTunnelRestClient.stopJob(clientId, engineJobId, false);
            log.info("Retried Engine stop for a canceling submission, instanceId={}, engineJobId={}",
                    instanceId, engineJobId);
        } catch (Exception e) {
            // Keep CANCELING so the same reserved id is checked and stopped again on a later pass.
            log.warn("Could not stop the Engine job for a canceling submission, instanceId={}, engineJobId={}",
                    instanceId, engineJobId, e);
        }
    }

    private String buildRecoveryMessage(Long instanceId,
                                        String engineJobId,
                                        ZetaJobStatusResolveResult result) {
        if (result == null) {
            return "SeaTunnel Web 重启后执行任务状态恢复失败，Zeta 状态解析结果为空。"
                    + " instanceId=" + instanceId
                    + ", engineJobId=" + engineJobId
                    + ", recoveryTime=" + new Date();
        }

        if (JobStatus.FINISHED.equals(result.getLocalStatus())) {
            return null;
        }

        StringBuilder builder = new StringBuilder();

        // The engine message can quote connector options; mask before the text is persisted and
        // republished (UI detail, OpenLineage terminal facet).
        builder.append("SeaTunnel Web 重启后执行任务状态恢复，发现该实例已不再处于 Zeta 运行中状态，已自动补偿本地状态。")
                .append(" instanceId=").append(instanceId)
                .append(", engineJobId=").append(engineJobId)
                .append(", engineStatus=").append(safe(result.getEngineStatus()))
                .append(", reason=").append(HoconSensitiveMaskUtil.maskSensitiveText(safe(result.getMessage())));

        if (result.getErrorMessage() != null && !result.getErrorMessage().trim().isEmpty()) {
            builder.append(", engineError=")
                    .append(HoconSensitiveMaskUtil.maskSensitiveText(result.getErrorMessage()));
        }

        builder.append(", recoveryTime=").append(new Date());

        return builder.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
