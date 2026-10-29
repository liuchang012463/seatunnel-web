package org.apache.seatunnel.web.api.service.impl;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.job.LakeJobGuard;
import org.apache.seatunnel.web.api.metrics.streaming.StreamingJobResultHandler;
import org.apache.seatunnel.web.api.metrics.streaming.StreamingJobSubmitter;
import org.apache.seatunnel.web.api.service.StreamingJobExecutorService;
import org.apache.seatunnel.web.api.service.StreamingJobInstanceService;
import org.apache.seatunnel.web.api.utils.HoconSensitiveMaskUtil;
import org.apache.seatunnel.web.api.utils.RedactedErrors;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.common.exception.JobSubmitException;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.engine.client.handler.ZetaJobStatusHandler;
import org.apache.seatunnel.web.engine.client.modal.ZetaJobStatusResolveResult;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class StreamingJobExecutorServiceImpl implements StreamingJobExecutorService {
    private static final String ZETA_SAVEPOINT_TOKEN_PREFIX = "zeta://savepoint/job/";

    private final StreamingJobInstanceService streamingJobInstanceService;
    private final StreamingJobDefinitionQueryService streamingJobDefinitionQueryService;
    private final StreamingJobSubmitter streamingJobSubmitter;
    private final ZetaJobStatusHandler zetaJobStatusHandler;
    private final LakeJobGuard lakeJobGuard;
    private final StreamingJobResultHandler streamingJobResultHandler;
    private final TaskExecutor jobSubmitExecutor;
    private final Set<Long> submittingDefinitionIds = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService submissionHeartbeat =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "streaming-submit-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    @Autowired
    public StreamingJobExecutorServiceImpl(StreamingJobInstanceService streamingJobInstanceService,
                                           StreamingJobDefinitionQueryService streamingJobDefinitionQueryService,
                                           StreamingJobSubmitter streamingJobSubmitter,
                                           ZetaJobStatusHandler zetaJobStatusHandler,
                                           LakeJobGuard lakeJobGuard,
                                           StreamingJobResultHandler streamingJobResultHandler,
                                           @Qualifier("jobSubmitExecutor") TaskExecutor jobSubmitExecutor) {
        this.streamingJobInstanceService = streamingJobInstanceService;
        this.streamingJobDefinitionQueryService = streamingJobDefinitionQueryService;
        this.streamingJobSubmitter = streamingJobSubmitter;
        this.zetaJobStatusHandler = zetaJobStatusHandler;
        this.lakeJobGuard = lakeJobGuard;
        this.streamingJobResultHandler = streamingJobResultHandler;
        this.jobSubmitExecutor = jobSubmitExecutor;
    }

    public StreamingJobExecutorServiceImpl(StreamingJobInstanceService streamingJobInstanceService,
                                           StreamingJobDefinitionQueryService streamingJobDefinitionQueryService,
                                           StreamingJobSubmitter streamingJobSubmitter,
                                           ZetaJobStatusHandler zetaJobStatusHandler,
                                           LakeJobGuard lakeJobGuard,
                                           StreamingJobResultHandler streamingJobResultHandler) {
        this(streamingJobInstanceService, streamingJobDefinitionQueryService,
                streamingJobSubmitter, zetaJobStatusHandler, lakeJobGuard,
                streamingJobResultHandler, Runnable::run);
    }

    public StreamingJobExecutorServiceImpl(StreamingJobInstanceService streamingJobInstanceService,
                                           StreamingJobDefinitionQueryService streamingJobDefinitionQueryService,
                                           StreamingJobSubmitter streamingJobSubmitter,
                                           ZetaJobStatusHandler zetaJobStatusHandler,
                                           LakeJobGuard lakeJobGuard) {
        this(streamingJobInstanceService, streamingJobDefinitionQueryService,
                streamingJobSubmitter, zetaJobStatusHandler, lakeJobGuard, null, Runnable::run);
    }

    public StreamingJobExecutorServiceImpl(StreamingJobInstanceService streamingJobInstanceService,
                                           StreamingJobDefinitionQueryService streamingJobDefinitionQueryService,
                                           StreamingJobSubmitter streamingJobSubmitter,
                                           ZetaJobStatusHandler zetaJobStatusHandler) {
        this(streamingJobInstanceService, streamingJobDefinitionQueryService,
                streamingJobSubmitter, zetaJobStatusHandler, null, null, Runnable::run);
    }

    @Override
    public Long jobExecute(Long jobDefineId, RunMode runMode) {
        validateDefinitionId(jobDefineId);
        validateRunMode(runMode);
        acquireSubmissionSlot(jobDefineId);
        boolean submissionQueued = false;
        ScheduledFuture<?> heartbeat = null;
        try {
            validateRunnable(jobDefineId);
            if (lakeJobGuard != null) {
                lakeJobGuard.validateBeforeExecute(jobDefineId, LakeJobRuntimeType.STREAMING);
            }

            if (streamingJobInstanceService.existsRunningInstance(jobDefineId)) {
                throw new ServiceException(
                        Status.JOB_DEFINITION_EXECUTE_ERROR,
                        "streaming job already has a running instance"
                );
            }

            // Keep the previous row snapshot so its Engine status can be checked by the worker.
            // The Zeta request can be slow, so it must not hold the HTTP request open.
            StreamingJobInstance previousInstance = streamingJobInstanceService.lastInstance(jobDefineId);

            JobInstanceVO instance = streamingJobInstanceService.createIfNoRunningInstance(
                    jobDefineId, runMode, JobMode.STREAMING);

            log.info("Streaming job execute requested: jobDefineId={}, runMode={}, instanceId={}",
                    jobDefineId, runMode, instance.getId());

            try {
                if (lakeJobGuard != null) {
                    lakeJobGuard.validateBeforeSubmit(instance, LakeJobRuntimeType.STREAMING);
                }
            } catch (Exception e) {
                failInstance(instance, e);
                throw e;
            }

            heartbeat = queueSubmission(instance, null, jobDefineId, previousInstance);
            submissionQueued = true;

            return instance.getId();
        } finally {
            if (!submissionQueued) {
                cancelHeartbeat(heartbeat);
                submittingDefinitionIds.remove(jobDefineId);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long jobPause(Long jobInstanceId) {
        validateInstanceId(jobInstanceId);

        JobInstanceVO instance = getInstanceOrThrow(jobInstanceId);

        if (isFinishedStatus(instance.getJobStatus())) {
            log.info("Streaming job instance already finished, skip pause: instanceId={}, status={}",
                    jobInstanceId, instance.getJobStatus());
            return jobInstanceId;
        }

        log.info("Streaming job pause requested: instanceId={}, status={}",
                jobInstanceId, instance.getJobStatus());

        if (JobStatus.CANCELING.name().equalsIgnoreCase(instance.getJobStatus())) {
            try {
                streamingJobSubmitter.pause(instance);
            } catch (Exception e) {
                log.info("Streaming cancellation is already pending: instanceId={}, errorType={}, error={}",
                        jobInstanceId, RedactedErrors.typeOf(e), RedactedErrors.mask(e.getMessage()));
            }
            return jobInstanceId;
        }

        if (JobStatus.STARTING.name().equalsIgnoreCase(instance.getJobStatus())) {
            String message = "Streaming job was canceled while Engine submission was in flight.";
            boolean markedCanceling = streamingJobResultHandler != null
                    ? streamingJobResultHandler.handleCanceling(jobInstanceId, instance.getEngineJobId(), message)
                    : streamingJobInstanceService.markCancelingIfStarting(
                            jobInstanceId, instance.getEngineJobId(), message);
            if (markedCanceling) {
                try {
                    streamingJobSubmitter.pause(instance);
                } catch (Exception e) {
                    // The upload can be accepted after this stop attempt. Recovery keeps the
                    // reserved id and retries until the Engine confirms a terminal state.
                    log.info("Streaming submission cancellation requested: instanceId={}, errorType={}, error={}",
                            jobInstanceId, RedactedErrors.typeOf(e), RedactedErrors.mask(e.getMessage()));
                }
                return jobInstanceId;
            }

            instance = streamingJobInstanceService.selectById(jobInstanceId);
            if (instance == null || isFinishedStatus(instance.getJobStatus())) {
                return jobInstanceId;
            }
            if (JobStatus.CANCELING.name().equalsIgnoreCase(instance.getJobStatus())) {
                try {
                    streamingJobSubmitter.pause(instance);
                } catch (Exception e) {
                    log.info("Streaming cancellation is already pending: instanceId={}, errorType={}, error={}",
                            jobInstanceId, RedactedErrors.typeOf(e), RedactedErrors.mask(e.getMessage()));
                }
                return jobInstanceId;
            }
        }

        try {
            streamingJobSubmitter.pause(instance);

            // Finalize through the shared handler so the streaming metrics monitor is released
            // and the engine log is persisted instead of waiting for its timeout.
            if (streamingJobResultHandler != null) {
                streamingJobResultHandler.handleCanceled(
                        jobInstanceId, "Streaming job was manually paused by user.");
            } else {
                StreamingJobInstance update = new StreamingJobInstance();
                update.setId(jobInstanceId);
                update.setJobStatus(JobStatus.CANCELED);
                update.setEndTime(new Date());
                update.setErrorMessage("Streaming job was manually paused by user.");

                streamingJobInstanceService.updateById(update);
            }

            log.info("Streaming job pause success: instanceId={}", jobInstanceId);
            return jobInstanceId;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Streaming job pause failed: instanceId={}", jobInstanceId, e);

            StreamingJobInstance update = new StreamingJobInstance();
            update.setId(jobInstanceId);
            // The engine text can carry connector credentials, so it is masked before it
            // reaches the instance row.
            update.setErrorMessage("Streaming job pause failed: "
                    + HoconSensitiveMaskUtil.maskSensitiveText(e.getMessage()));

            streamingJobInstanceService.updateById(update);

            throw new ServiceException(Status.JOB_DEFINITION_EXECUTE_ERROR);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long jobStopWithSavepoint(Long jobInstanceId) {
        validateInstanceId(jobInstanceId);

        JobInstanceVO instance = getInstanceOrThrow(jobInstanceId);

        if (isFinishedStatus(instance.getJobStatus())) {
            if (hasSavepointToken(instance)) {
                log.info("Streaming job instance already stopped with savepoint, skip stop: instanceId={}, savepointPath={}",
                        jobInstanceId, instance.getSavepointPath());
                return jobInstanceId;
            }

            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "streaming job is already finished and has no savepoint"
            );
        }

        String engineJobId = instance.getEngineJobId();
        if (engineJobId == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "engineJobId");
        }

        log.info("Streaming job stop with savepoint requested: instanceId={}, engineJobId={}, status={}",
                jobInstanceId, engineJobId, instance.getJobStatus());

        try {
            streamingJobSubmitter.stopWithSavepoint(instance);

            StreamingJobInstance update = new StreamingJobInstance();
            update.setId(jobInstanceId);

            /*
             * If your JobStatus enum has SAVEPOINT_DONE, you can use it here.
             * To keep compatibility with the current project, we keep CANCELED
             * and use savepointPath to distinguish normal cancel and savepoint stop.
             */
            update.setJobStatus(JobStatus.CANCELED);
            update.setEndTime(new Date());
            update.setSavepointPath(buildSavepointToken(engineJobId));
            update.setErrorMessage("Streaming job was manually stopped with savepoint.");

            streamingJobInstanceService.updateById(update);

            log.info("Streaming job stop with savepoint success: instanceId={}, engineJobId={}, savepointToken={}",
                    jobInstanceId, engineJobId, buildSavepointToken(engineJobId));

            return jobInstanceId;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Streaming job stop with savepoint failed: instanceId={}", jobInstanceId, e);

            StreamingJobInstance update = new StreamingJobInstance();
            update.setId(jobInstanceId);
            update.setErrorMessage("Streaming job stop with savepoint failed: "
                    + HoconSensitiveMaskUtil.maskSensitiveText(e.getMessage()));

            streamingJobInstanceService.updateById(update);

            throw new ServiceException(Status.JOB_DEFINITION_EXECUTE_ERROR);
        }
    }

    @Override
    public Long jobResumeFromSavepoint(Long sourceJobInstanceId, RunMode runMode) {
        validateInstanceId(sourceJobInstanceId);
        validateRunMode(runMode);
        JobInstanceVO sourceInstance = getInstanceOrThrow(sourceJobInstanceId);
        Long jobDefineId = sourceInstance.getJobDefinitionId();
        validateDefinitionId(jobDefineId);
        acquireSubmissionSlot(jobDefineId);
        boolean submissionQueued = false;
        ScheduledFuture<?> heartbeat = null;
        try {
            if (isRunningStatus(sourceInstance.getJobStatus())) {
                throw new ServiceException(
                        Status.JOB_DEFINITION_EXECUTE_ERROR,
                        "running streaming job can not be used as savepoint source"
                );
            }

            validateRunnable(jobDefineId);
            if (lakeJobGuard != null) {
                lakeJobGuard.validateBeforeExecute(jobDefineId, LakeJobRuntimeType.STREAMING);
            }

            if (streamingJobInstanceService.existsRunningInstance(jobDefineId)) {
                throw new ServiceException(
                        Status.JOB_DEFINITION_EXECUTE_ERROR,
                        "streaming job already has a running instance"
                );
            }

            String restoreEngineJobId = resolveRestoreEngineJobId(sourceInstance);
            JobInstanceVO newInstance = streamingJobInstanceService.createIfNoRunningInstance(
                    jobDefineId, runMode, JobMode.STREAMING);

            log.info(
                    "Streaming job resume from savepoint requested: sourceInstanceId={}, newInstanceId={}, jobDefineId={}, restoreEngineJobId={}",
                    sourceJobInstanceId,
                    newInstance.getId(),
                    jobDefineId,
                    restoreEngineJobId
            );

            StreamingJobInstance update = new StreamingJobInstance();
            update.setId(newInstance.getId());
            update.setCheckpointPath(sourceInstance.getCheckpointPath());
            update.setSavepointPath(sourceInstance.getSavepointPath());
            // Engine 3.0 reuses the source Engine job ID for SAVEPOINT restores.
            update.setEngineJobId(restoreEngineJobId);
            update.setErrorMessage("Streaming job was resumed from savepoint, sourceInstanceId=" + sourceJobInstanceId);
            streamingJobInstanceService.updateById(update);

            try {
                if (lakeJobGuard != null) {
                    lakeJobGuard.validateBeforeSubmit(newInstance, LakeJobRuntimeType.STREAMING);
                }
            } catch (Exception e) {
                failInstance(newInstance, e);
                throw e;
            }

            heartbeat = queueSubmission(newInstance, restoreEngineJobId, jobDefineId, null);
            submissionQueued = true;
            return newInstance.getId();
        } finally {
            if (!submissionQueued) {
                cancelHeartbeat(heartbeat);
                submittingDefinitionIds.remove(jobDefineId);
            }
        }
    }

    private ScheduledFuture<?> queueSubmission(JobInstanceVO instance,
                                               String restoreEngineJobId,
                                               Long jobDefinitionId,
                                               StreamingJobInstance previousInstance) {
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = submissionHeartbeat.scheduleAtFixedRate(
                    () -> streamingJobInstanceService.touchStartingInstance(instance.getId()),
                    60, 60, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            failInstance(instance, e);
            throw e;
        }

        try {
            jobSubmitExecutor.execute(() -> submitStreamingJob(
                    instance, restoreEngineJobId, jobDefinitionId, previousInstance, heartbeat));
            return heartbeat;
        } catch (RuntimeException e) {
            cancelHeartbeat(heartbeat);
            failInstance(instance, e);
            throw e;
        }
    }

    private void submitStreamingJob(JobInstanceVO instance,
                                    String restoreEngineJobId,
                                    Long jobDefinitionId,
                                    StreamingJobInstance previousInstance,
                                    ScheduledFuture<?> heartbeat) {
        try {
            JobInstanceVO latest = streamingJobInstanceService.selectById(instance.getId());
            if (latest == null || !JobStatus.STARTING.name().equalsIgnoreCase(latest.getJobStatus())) {
                log.info("Skip streaming submission because the instance left STARTING while queued, instanceId={}, status={}",
                        instance.getId(), latest == null ? null : latest.getJobStatus());
                if (latest != null && JobStatus.CANCELING.name().equalsIgnoreCase(latest.getJobStatus())) {
                    if (streamingJobResultHandler != null) {
                        streamingJobResultHandler.skipSubmissionIfNotStarting(instance.getId(), latest.getEngineJobId());
                    } else {
                        streamingJobInstanceService.updateStatus(
                                instance.getId(), JobStatus.CANCELED,
                                "Streaming job was canceled before Engine submission.");
                    }
                }
                return;
            }
            if (restoreEngineJobId == null
                    && previousInstance != null
                    && !reconcilePreviousInstance(latest, previousInstance)) {
                if (streamingJobResultHandler != null) {
                    streamingJobResultHandler.skipSubmissionIfNotStarting(
                            instance.getId(), latest.getEngineJobId());
                }
                return;
            }
            if (restoreEngineJobId == null) {
                streamingJobSubmitter.submit(latest);
            } else {
                streamingJobSubmitter.submitFromSavepoint(latest, restoreEngineJobId);
            }
        } catch (Exception e) {
            log.error("Asynchronous streaming job submission failed: instanceId={}, errorType={}, error={}",
                    instance.getId(),
                    RedactedErrors.typeOf(e),
                    RedactedErrors.mask(e.getMessage()),
                    RedactedErrors.redacted(e, RedactedErrors.mask(e.getMessage())));
            if (!isSubmitOutcomeUnknown(e)) {
                try {
                    JobInstanceVO latest = streamingJobInstanceService.selectById(instance.getId());
                    if (latest != null
                            && JobStatus.STARTING.name().equalsIgnoreCase(latest.getJobStatus())) {
                        failInstance(instance, e);
                    }
                } catch (Exception stateCheckError) {
                    log.warn("Could not inspect streaming instance after async submit failure, instanceId={}",
                            instance.getId(), stateCheckError);
                }
            }
        } finally {
            cancelHeartbeat(heartbeat);
            submittingDefinitionIds.remove(jobDefinitionId);
        }
    }

    /**
     * Checks whether an earlier local instance still has a live Engine job before submitting a
     * replacement. Unknown responses keep the new row STARTING with the earlier Engine id so the
     * recovery scheduler can retry without launching a duplicate Engine job.
     */
    private boolean reconcilePreviousInstance(JobInstanceVO current, StreamingJobInstance previous) {
        if (previous.getClientId() == null || StringUtils.isBlank(previous.getEngineJobId())) {
            return true;
        }

        try {
            ZetaJobStatusResolveResult result = zetaJobStatusHandler.resolve(
                    previous.getClientId(), previous.getEngineJobId());
            if (result != null && result.isRunning()) {
                String errorMessage = "SeaTunnel Web 检测到上一次流任务仍在 Engine 运行，已恢复旧实例状态。"
                        + " instanceId=" + previous.getId()
                        + ", engineJobId=" + previous.getEngineJobId()
                        + ", recoveryTime=" + new Date();
                boolean updated = streamingJobInstanceService.updateStatusIfSnapshot(
                        previous.getId(),
                        previous.getJobStatus(),
                        previous.getEngineJobId(),
                        lastActivity(previous),
                        result.getLocalStatus(),
                        errorMessage);
                if (!updated) {
                    preserveUnknownPreviousInstance(current, previous,
                            "上一次实例在 Engine 核验期间发生变化");
                    return false;
                }
                failInstance(current, new ServiceException(
                        Status.JOB_DEFINITION_EXECUTE_RECOVERY,
                        "上一次流任务仍在 Engine 运行，本次提交已取消。"));
                return false;
            }
            if (result != null
                    && (result.getLocalStatus() != JobStatus.UNKNOWABLE || result.isFound())) {
                return true;
            }
            preserveUnknownPreviousInstance(current, previous,
                    result == null ? "Engine 状态返回为空" : result.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Resolve previous streaming Engine job failed, previousInstanceId={}, engineJobId={}",
                    previous.getId(), previous.getEngineJobId(), e);
            preserveUnknownPreviousInstance(current, previous, RedactedErrors.mask(e.getMessage()));
            return false;
        }
    }

    private void preserveUnknownPreviousInstance(JobInstanceVO current,
                                                 StreamingJobInstance previous,
                                                 String reason) {
        String message = "无法确认上一次流任务是否仍在 Engine 运行，已暂停新任务提交并等待自动核验。"
                + (StringUtils.isBlank(reason) ? "" : "原因：" + reason);
        boolean updated = streamingJobInstanceService.replaceEngineIdIfStarting(
                current.getId(),
                current.getEngineJobId(),
                previous.getEngineJobId(),
                message);
        if (!updated) {
            log.info("Skip previous Engine id handoff because the new instance left its reserved STARTING state, "
                            + "instanceId={}, engineJobId={}",
                    current.getId(), current.getEngineJobId());
        }
    }

    private static Date lastActivity(StreamingJobInstance instance) {
        return instance.getUpdateTime() != null ? instance.getUpdateTime() : instance.getCreateTime();
    }

    private void failInstance(JobInstanceVO instance, Throwable error) {
        if (streamingJobResultHandler != null) {
            streamingJobResultHandler.handleFailure(instance.getId(), error);
            return;
        }

        StreamingJobInstance update = new StreamingJobInstance();
        update.setId(instance.getId());
        update.setJobStatus(JobStatus.FAILED);
        update.setEndTime(new Date());
        update.setErrorMessage(error == null
                ? "Streaming job submission failed"
                : HoconSensitiveMaskUtil.maskSensitiveText(error.getMessage()));
        streamingJobInstanceService.updateById(update);
    }

    private void acquireSubmissionSlot(Long jobDefinitionId) {
        if (!submittingDefinitionIds.add(jobDefinitionId)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "任务已在提交队列中，请勿重复启动。任务ID：" + jobDefinitionId
            );
        }
    }

    private static boolean isSubmitOutcomeUnknown(Throwable error) {
        return error instanceof JobSubmitException
                && ((JobSubmitException) error).isOutcomeUnknown();
    }

    private static void cancelHeartbeat(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    private void validateRunMode(RunMode runMode) {
        if (runMode == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "runMode");
        }
    }

    @PreDestroy
    public void shutdownSubmissionHeartbeat() {
        submissionHeartbeat.shutdownNow();
    }

    private JobInstanceVO getInstanceOrThrow(Long jobInstanceId) {
        JobInstanceVO instance = streamingJobInstanceService.selectById(jobInstanceId);
        if (instance == null || instance.getId() == null) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
        }
        return instance;
    }

    private void validateRunnable(Long jobDefineId) {
        StreamingJobDefinitionEntity definition =
                streamingJobDefinitionQueryService.getDefinitionOrThrow(jobDefineId);

        if (definition.getReleaseState() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "releaseState");
        }

        if (definition.getReleaseState() != ReleaseState.ONLINE) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "only online streaming job can be executed"
            );
        }
    }

    private void validateDefinitionId(Long jobDefineId) {
        if (jobDefineId == null || jobDefineId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobDefineId");
        }
    }

    private void validateInstanceId(Long jobInstanceId) {
        if (jobInstanceId == null || jobInstanceId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobInstanceId");
        }
    }

    private boolean isFinishedStatus(String status) {
        if (status == null) {
            return false;
        }

        return "FINISHED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "STOPPED".equalsIgnoreCase(status)
                || "SAVEPOINT_DONE".equalsIgnoreCase(status);
    }

    private boolean isRunningStatus(String status) {
        if (status == null) {
            return false;
        }

        return "RUNNING".equalsIgnoreCase(status)
                || "SUBMITTED".equalsIgnoreCase(status)
                || "STARTING".equalsIgnoreCase(status)
                || "CREATED".equalsIgnoreCase(status);
    }

    private boolean hasSavepointToken(JobInstanceVO instance) {
        return instance != null && StringUtils.isNotBlank(instance.getSavepointPath());
    }

    private String buildSavepointToken(String engineJobId) {
        return ZETA_SAVEPOINT_TOKEN_PREFIX + engineJobId;
    }

    private String resolveRestoreEngineJobId(JobInstanceVO sourceInstance) {
        if (sourceInstance == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "sourceJobInstance");
        }

        String savepointPath = sourceInstance.getSavepointPath();
        if (StringUtils.isBlank(savepointPath)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "source job instance has no savepoint"
            );
        }

        String engineJobIdText = savepointPath.trim();

        if (engineJobIdText.startsWith(ZETA_SAVEPOINT_TOKEN_PREFIX)) {
            engineJobIdText = engineJobIdText.substring(ZETA_SAVEPOINT_TOKEN_PREFIX.length());
        }

        return engineJobIdText;
    }
}
