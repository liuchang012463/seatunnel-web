package org.apache.seatunnel.web.api.metrics;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageJobLineageService;
import org.apache.seatunnel.web.common.enums.JobSubmitStage;
import org.apache.seatunnel.web.common.exception.JobSubmitException;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.engine.client.driver.EngineDriverJarPublisher;
import org.apache.seatunnel.web.engine.client.exceptions.SeaTunnelClientException;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Submitter for batch SeaTunnel jobs.
 *
 * <p>
 * This class only handles batch job submission, metrics monitoring,
 * and result watching. Streaming jobs should use StreamingJobSubmitter.
 * </p>
 */
@Component
@Slf4j
public class BatchJobSubmitter {

    private static final String JOB_TYPE_BATCH = "BATCH";

    private final JobConfigFileService configFileService;
    private final SeaTunnelRestClient restClient;
    private final EngineDriverJarPublisher driverJarPublisher;
    private final JobMetricsMonitor batchMetricsMonitor;
    private final JobResultWatcher batchJobResultWatcher;
    private final JobResultHandler batchJobResultHandler;
    /** Absent when lineage-bridge.kafka is disabled; nothing is scheduled. */
    private final Optional<OpenLineageJobLineageService> openLineageJobLineageService;

    public BatchJobSubmitter(JobConfigFileService configFileService,
                             SeaTunnelRestClient restClient,
                             EngineDriverJarPublisher driverJarPublisher,
                             JobMetricsMonitor batchMetricsMonitor,
                             JobResultWatcher batchJobResultWatcher,
                             JobResultHandler batchJobResultHandler,
                             Optional<OpenLineageJobLineageService> openLineageJobLineageService) {
        this.configFileService = configFileService;
        this.restClient = restClient;
        this.driverJarPublisher = driverJarPublisher;
        this.batchMetricsMonitor = batchMetricsMonitor;
        this.batchJobResultWatcher = batchJobResultWatcher;
        this.batchJobResultHandler = batchJobResultHandler;
        this.openLineageJobLineageService = openLineageJobLineageService;
    }

    public void submit(JobInstanceVO instance) {
        validate(instance);

        Long instanceId = instance.getId();
        Long jobDefinitionId = instance.getJobDefinitionId();
        Long clientId = instance.getClientId();

        String logPath = instance.getLogPath();

        JobFileLogger jobLogger = new JobFileLogger(logPath);
        jobLogger.info("=== Batch Job Submit Start (REST API) ===");
        jobLogger.info("Batch instanceId: " + instanceId);
        jobLogger.info("Batch definitionId: " + jobDefinitionId);
        jobLogger.info("Client id: " + clientId);

        String hoconConfig = null;
        String configFile = null;
        String engineId = null;
        boolean submitted = false;
        boolean submitRequestStarted = false;

        try {
            // A driver publication failure must be handled as a local submission failure instead
            // of leaving the instance STARTING without a job log.
            hoconConfig = driverJarPublisher.publish(clientId, instance.getRuntimeConfig());

            jobLogger.info("Writing batch config file...");
            configFile = configFileService.writeConfig(instanceId, hoconConfig);
            jobLogger.info("Batch config file written to: " + configFile);

            jobLogger.info("Submitting batch job via REST API...");
            log.info("Submitting batch job to Zeta, instanceId={}, clientId={}", instanceId, clientId);

            String filename = "batch-job-" + instanceId + ".conf";

            if (batchJobResultHandler.skipSubmissionIfNotStarting(instanceId, instance.getEngineJobId())) {
                jobLogger.info("Skip Engine upload because the local submission is no longer STARTING");
                return;
            }

            submitRequestStarted = true;
            Map<?, ?> resp = restClient.submitJobUpload(
                    clientId,
                    safeConfig(hoconConfig).getBytes(StandardCharsets.UTF_8),
                    filename,
                    instance.getEngineJobId(),
                    null,
                    false
            );

            engineId = extractJobId(resp);
            if (!engineId.equals(instance.getEngineJobId())) {
                // The Engine created a job under another id. That job is invisible to this web
                // (recovery only reconciles the reserved id), so it is stopped instead of being
                // left running untracked; a stop failure is logged and the submit still fails.
                String unexpectedJobId = engineId;
                try {
                    restClient.stopJob(clientId, unexpectedJobId, false);
                    jobLogger.warn("Stopped an Engine job that came back with an unexpected id: "
                            + unexpectedJobId);
                } catch (Exception stopError) {
                    log.warn("Could not stop the Engine job with the unexpected id, instanceId={}, "
                            + "engineJobId={}", instanceId, unexpectedJobId, stopError);
                }
                throw new IllegalStateException("Engine returned a different jobId: " + engineId);
            }
            submitted = true;

            // The Engine has accepted the instance and returned its job id. The lineage
            // event is published on the Kafka bridge with the local identity only; every
            // failure is logged, never rethrown, and never delays the settled submission.
            String acceptedEngineJobId = engineId;
            try {
                openLineageJobLineageService.ifPresent(
                        lineageService -> lineageService.recordStart(instance, acceptedEngineJobId, false));
            } catch (Exception lineageError) {
                log.warn("Could not schedule OpenLineage lineage for instanceId={}", instanceId, lineageError);
            }

            log.info("Submit batch job response received, instanceId={}, resp={}", instanceId, resp);
            jobLogger.info("Submit batch job response received: " + resp);

            batchJobResultHandler.updateEngineId(instanceId, engineId);

            JobRuntimeContext ctx = buildRuntimeContext(
                    instance,
                    engineId,
                    configFile
            );

            registerPostSubmitWatchers(ctx, jobLogger);

            jobLogger.info("=== Batch Job Submit Complete ===");
        } catch (Exception e) {
            if (submitted) {
                handlePostSubmitFailure(jobLogger, instanceId, engineId, e);
                return;
            }

            if (submitRequestStarted) {
                handleSubmitRequestFailure(jobLogger, instanceId, e);
                return;
            }

            handleCoreFailure(jobLogger, instanceId, e);
        } finally {
            jobLogger.close();
        }
    }

    public void pause(JobInstanceVO instance) {
        if (instance == null || instance.getId() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobInstance");
        }

        if (instance.getClientId() == null || instance.getClientId() <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "clientId");
        }

        if (instance.getEngineJobId() == null ) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "engineJobId");
        }

        Long instanceId = instance.getId();
        Long clientId = instance.getClientId();
        String engineJobId = instance.getEngineJobId();

        log.info("Stopping batch SeaTunnel job: instanceId={}, clientId={}, engineJobId={}",
                instanceId, clientId, engineJobId);

        Map<?, ?> resp = restClient.stopJob(clientId, engineJobId, false);

        log.info("Stop batch SeaTunnel job response: instanceId={}, clientId={}, engineJobId={}, resp={}",
                instanceId, clientId, engineJobId, resp);
    }

    private void registerPostSubmitWatchers(JobRuntimeContext ctx,
                                            JobFileLogger jobLogger) {
        boolean metricsRegistered = false;
        boolean watcherRegistered = false;

        try {
            batchMetricsMonitor.register(ctx);
            metricsRegistered = true;

            jobLogger.info("Batch metrics monitor registered");
            log.info("Batch metrics monitor registered, instanceId={}, engineId={}",
                    ctx.getInstanceId(), ctx.getEngineId());
        } catch (Exception e) {
            jobLogger.warn("Batch metrics monitor register failed: " + e.getMessage());

            log.warn("Batch metrics monitor register failed, instanceId={}, engineId={}",
                    ctx.getInstanceId(), ctx.getEngineId(), e);
        }

        try {
            batchJobResultWatcher.registerByRest(ctx);
            watcherRegistered = true;

            jobLogger.info("Batch REST result watcher registered");
            log.info("Batch REST result watcher registered, instanceId={}, engineId={}",
                    ctx.getInstanceId(), ctx.getEngineId());
        } catch (Exception e) {
            jobLogger.warn("Batch result watcher register failed: " + e.getMessage());

            log.warn("Batch result watcher register failed, instanceId={}, engineId={}",
                    ctx.getInstanceId(), ctx.getEngineId(), e);
        }

        if (!metricsRegistered || !watcherRegistered) {
            jobLogger.warn(
                    "Batch post-submit watcher registration incomplete. " +
                            "The job has already been submitted to SeaTunnel Engine. " +
                            "metricsRegistered=" + metricsRegistered +
                            ", watcherRegistered=" + watcherRegistered
            );
        }
    }

    private JobRuntimeContext buildRuntimeContext(JobInstanceVO instance,
                                                  String engineId,
                                                  String configFile) {
        JobRuntimeContext ctx = new JobRuntimeContext();

        ctx.setInstanceId(instance.getId());
        ctx.setJobDefinitionId(instance.getJobDefinitionId());
        ctx.setClientId(instance.getClientId());
        ctx.setEngineId(engineId);
        ctx.setConfigFile(configFile);
        ctx.setJobType(JOB_TYPE_BATCH);

        return ctx;
    }

    private void handleCoreFailure(JobFileLogger jobLogger,
                                   Long instanceId,
                                   Exception e) {
        jobLogger.error("Batch job submit failed before engine accepted the job", e);

        try {
            batchJobResultHandler.handleFailure(instanceId, e);
        } catch (Exception handlerEx) {
            log.error("Batch handleFailure threw exception, instanceId={}", instanceId, handlerEx);
        }

        throw (e instanceof JobSubmitException)
                ? (JobSubmitException) e
                : new JobSubmitException(JobSubmitStage.SUBMIT, "Submit batch job failed", e);
    }

    private void handleSubmitRequestFailure(JobFileLogger jobLogger,
                                            Long instanceId,
                                            Exception e) {
        if (isSubmitOutcomeAmbiguous(e)) {
            // The reserved Engine id is durable. Keep STARTING or CANCELING so recovery can
            // query that exact id; never retry an uncertain network submission.
            jobLogger.error("Batch submission response unavailable; automatic reconciliation pending", e);

            throw new JobSubmitException(
                    JobSubmitStage.SUBMIT,
                    "Submit batch job failed before an Engine job id was received",
                    e,
                    true
            );
        }

        jobLogger.error("Batch job submission failed before an Engine job id was received", e);
        try {
            batchJobResultHandler.handleFailure(instanceId, e);
        } catch (Exception handlerEx) {
            log.error("Record batch submit failure failed, instanceId={}", instanceId, handlerEx);
        }

        throw new JobSubmitException(
                JobSubmitStage.SUBMIT,
                "Submit batch job failed before an Engine job id was received",
                e
        );
    }

    /**
     * A submit failure is only safe to record as FAILED when the request provably never reached
     * the Engine: a refused or unresolvable connection, or a request the Engine rejected with a
     * 4xx. A read timeout, a 5xx or an unknown transport error may have been accepted, so the
     * instance stays STARTING and blocks further submits until automatic reconciliation.
     */
    static boolean isSubmitOutcomeAmbiguous(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SeaTunnelClientException) {
                int httpStatus = ((SeaTunnelClientException) current).getHttpStatus();
                if (httpStatus >= 400 && httpStatus < 500) {
                    return false;
                }
                if (httpStatus >= 500) {
                    String body = ((SeaTunnelClientException) current).getResponseBody();
                    // These errors occur during source/sink construction, before job submission.
                    boolean catalogRejected = body != null && body.contains("COMMON-21")
                            && body.contains("unsupported get catalog table");
                    boolean factoryRejected = body != null && body.contains("ErrorCode:[API-06]")
                            && body.contains("Factory initialize failed");
                    return !catalogRejected && !factoryRejected;
                }
            }

            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException) {
                return false;
            }

            if (current.getCause() == current) {
                break;
            }
        }

        return true;
    }

    private void handlePostSubmitFailure(JobFileLogger jobLogger,
                                         Long instanceId,
                                         String engineId,
                                         Exception e) {
        jobLogger.error(
                "Batch job was submitted to SeaTunnel Engine, but post-submit handling failed. " +
                        "instanceId=" + instanceId + ", engineId=" + engineId,
                e
        );

        log.warn(
                "Batch post-submit handling failed after job accepted by engine, instanceId={}, engineId={}",
                instanceId,
                engineId,
                e
        );

        /*
         * Do not mark the batch job instance as FAILED here.
         *
         * Reason:
         * The SeaTunnel Engine has already accepted the job.
         * If we mark the local instance as failed now, local state may conflict
         * with the real engine state.
         */
    }

    private String extractJobId(Map<?, ?> resp) {
        Object jobIdObj = resp == null ? null : resp.get("jobId");

        if (jobIdObj == null || StringUtils.isBlank(jobIdObj.toString())) {
            throw new IllegalStateException("REST submit response missing jobId, resp=" + resp);
        }

        return jobIdObj.toString();
    }

    private void validate(JobInstanceVO instance) {
        if (instance == null) {
            throw new IllegalArgumentException("Batch job instance must not be null");
        }

        if (instance.getId() == null) {
            throw new IllegalArgumentException("Batch job instance id must not be null");
        }

        if (instance.getClientId() == null) {
            throw new IllegalArgumentException("Batch job client id must not be null");
        }

        if (StringUtils.isBlank(instance.getLogPath())) {
            throw new IllegalArgumentException("Batch job log path must not be blank");
        }

        if (StringUtils.isBlank(instance.getEngineJobId())) {
            throw new IllegalArgumentException("Batch job must reserve an Engine job id before submission");
        }

        if (StringUtils.isBlank(instance.getRuntimeConfig())) {
            throw new IllegalArgumentException("Batch job runtime config must not be blank");
        }
    }

    private String safeConfig(String config) {
        return config == null ? "" : config;
    }
}
