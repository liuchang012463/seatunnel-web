package org.apache.seatunnel.web.api.client.scheduler;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metrics.JobResultHandler;
import org.apache.seatunnel.web.api.metrics.JobRuntimeContext;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.engine.client.handler.ZetaJobStatusHandler;
import org.apache.seatunnel.web.engine.client.modal.ZetaJobStatusResolveResult;
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
    // job_instance timestamps use second precision; floor the boundary so a new
    // request in the same second cannot be mistaken for a previous-process row.
    private final Date processStartedAt = new Date(System.currentTimeMillis() / 1_000 * 1_000);

    @Resource
    private JobInstanceDao jobInstanceDao;

    @Resource
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @Resource
    private ZetaJobStatusHandler zetaJobStatusHandler;

    @Resource
    private JobResultHandler jobResultHandler;

    @Value("${seatunnel.job.recovery.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!enabled) {
            return;
        }

        log.info("Start recovering running job instances after SeaTunnel Web startup");

        markInterruptedBatchSubmissions();
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

    /**
     * An in-memory submission queue is lost on process restart. Keep ambiguous submissions
     * visible and block automatic retries, since Engine may have accepted a request before
     * the Web process stopped or timed out.
     */
    private void markInterruptedBatchSubmissions() {
        List<JobInstance> instances = jobInstanceDao.listRunningLikeInstances();
        if (instances == null || instances.isEmpty()) {
            return;
        }

        for (JobInstance instance : instances) {
            try {
                if (instance == null
                        || instance.getId() == null
                        || !JobStatus.STARTING.equals(instance.getJobStatus())
                        || (instance.getEngineJobId() != null && !instance.getEngineJobId().isBlank())
                        || (instance.getCreateTime() != null
                        && !instance.getCreateTime().before(processStartedAt))
                        // A rolling restart can start this instance while another Web instance is
                        // still submitting an older row. Only a submission with no heartbeat for
                        // the full grace window is treated as interrupted.
                        || !isStaleSubmission(instance)) {
                    continue;
                }

                String message = "SeaTunnel Web 在提交任务时重启，未保存到 Engine 作业 ID；任务可能已被 Engine 接收。"
                        + "请先核对 SeaTunnel Engine 作业列表，再决定是否重新启动。";
                jobResultHandler.handleSubmissionUnknown(instance.getId(), message);
                log.warn("Marked interrupted batch submission as unknown, instanceId={}", instance.getId());
            } catch (Exception e) {
                // One bad row must not stop the remaining startup recovery steps.
                log.warn("Mark interrupted batch submission failed, instanceId={}",
                        instance == null ? null : instance.getId(), e);
            }
        }
    }

    private boolean isStaleSubmission(JobInstance instance) {
        Date lastActivity = instance.getUpdateTime() != null
                ? instance.getUpdateTime()
                : instance.getCreateTime();
        return lastActivity != null
                && lastActivity.getTime() < System.currentTimeMillis() - SUBMISSION_GRACE_MS;
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

        // A batch submit is still running asynchronously until Engine returns an id.
        // A missing id at this point is not evidence that the Engine job has failed, but a
        // submission that never produced an id within the grace window is stuck: the async task
        // was lost without a restart (rejected queue, dropped task). Hand it to the operator as
        // an ambiguous submission instead of leaving it STARTING forever.
        if (JobStatus.STARTING.equals(instance.getJobStatus())
                && (instance.getEngineJobId() == null || instance.getEngineJobId().isBlank())) {
            if (isStaleSubmission(instance)) {
                String message = "SeaTunnel Web 提交该任务后长时间未收到 Engine 作业 ID；"
                        + "任务可能已被 Engine 接收。请先核对 SeaTunnel Engine 作业列表，再决定是否重新启动。";
                jobResultHandler.handleSubmissionUnknown(instance.getId(), message);
                log.warn("Marked stale batch submission as unknown, instanceId={}", instance.getId());
                return;
            }

            log.debug("Skip recovering batch instance while submission is in progress, instanceId={}",
                    instance.getId());
            return;
        }

        ZetaJobStatusResolveResult result = zetaJobStatusHandler.resolve(
                instance.getClientId(),
                instance.getEngineJobId()
        );

        if (shouldSkipRecovery(instance.getId(), instance.getEngineJobId(), result)) {
            return;
        }

        JobStatus targetStatus = result.getLocalStatus();

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
        jobResultHandler.handleFinalStatus(
                context,
                targetStatus,
                errorMessage
        );
    }

    private void recoverStreamingInstance(StreamingJobInstance instance) {
        if (instance == null || instance.getId() == null) {
            return;
        }

        ZetaJobStatusResolveResult result = zetaJobStatusHandler.resolve(
                instance.getClientId(),
                instance.getEngineJobId()
        );

        if (shouldSkipRecovery(instance.getId(), instance.getEngineJobId(), result)) {
            return;
        }

        JobStatus targetStatus = result.getLocalStatus();

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

        streamingJobInstanceDao.updateStatus(
                instance.getId(),
                targetStatus,
                errorMessage
        );
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

        if (JobStatus.UNKNOWABLE.equals(localStatus)) {
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

        builder.append("SeaTunnel Web 重启后执行任务状态恢复，发现该实例已不再处于 Zeta 运行中状态，已自动补偿本地状态。")
                .append(" instanceId=").append(instanceId)
                .append(", engineJobId=").append(engineJobId)
                .append(", engineStatus=").append(safe(result.getEngineStatus()))
                .append(", reason=").append(safe(result.getMessage()));

        if (result.getErrorMessage() != null && !result.getErrorMessage().trim().isEmpty()) {
            builder.append(", engineError=").append(result.getErrorMessage());
        }

        builder.append(", recoveryTime=").append(new Date());

        return builder.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
