package org.apache.seatunnel.web.api.metrics.streaming;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageJobLineageService;
import org.apache.seatunnel.web.api.metrics.JobConfigFileService;
import org.apache.seatunnel.web.api.metrics.JobFileLogger;
import org.apache.seatunnel.web.api.metrics.JobRuntimeContext;
import org.apache.seatunnel.web.common.enums.JobSubmitStage;
import org.apache.seatunnel.web.common.exception.JobSubmitException;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.engine.client.exceptions.SeaTunnelClientException;
import org.apache.seatunnel.web.engine.client.driver.EngineDriverJarPublisher;
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

@Component
@Slf4j
public class StreamingJobSubmitter {

    private static final String JOB_TYPE_STREAMING = "STREAMING";

    private final JobConfigFileService configFileService;
    private final SeaTunnelRestClient restClient;
    private final EngineDriverJarPublisher driverJarPublisher;
    private final StreamingJobMetricsMonitor streamingJobMetricsMonitor;
    private final StreamingJobResultWatcher streamingJobResultWatcher;
    private final StreamingJobResultHandler streamingJobResultHandler;
    /** Absent when lineage-bridge.kafka is disabled; nothing is scheduled. */
    private final Optional<OpenLineageJobLineageService> openLineageJobLineageService;

    public StreamingJobSubmitter(JobConfigFileService configFileService,
                                 SeaTunnelRestClient restClient,
                                 EngineDriverJarPublisher driverJarPublisher,
                                 StreamingJobMetricsMonitor streamingJobMetricsMonitor,
                                 StreamingJobResultWatcher streamingJobResultWatcher,
                                 StreamingJobResultHandler streamingJobResultHandler,
                                 Optional<OpenLineageJobLineageService> openLineageJobLineageService) {
        this.configFileService = configFileService;
        this.restClient = restClient;
        this.driverJarPublisher = driverJarPublisher;
        this.streamingJobMetricsMonitor = streamingJobMetricsMonitor;
        this.streamingJobResultWatcher = streamingJobResultWatcher;
        this.streamingJobResultHandler = streamingJobResultHandler;
        this.openLineageJobLineageService = openLineageJobLineageService;
    }

    public void submit(JobInstanceVO instance) {
        submit(instance, StreamingJobSubmitOptions.normal());
    }

    public void submitFromSavepoint(JobInstanceVO instance, String restoreEngineJobId) {
        submit(instance, StreamingJobSubmitOptions.restoreFromSavepoint(restoreEngineJobId));
    }

    public void submit(JobInstanceVO instance, StreamingJobSubmitOptions options) {
        validate(instance);
        validateSubmitOptions(options);

        Long instanceId = instance.getId();
        Long jobDefinitionId = instance.getJobDefinitionId();
        Long clientId = instance.getClientId();

        String logPath = instance.getLogPath();

        JobFileLogger jobLogger = new JobFileLogger(logPath);
        jobLogger.info("=== Streaming Job Submit Start (REST API) ===");
        jobLogger.info("Streaming instanceId: " + instanceId);
        jobLogger.info("Streaming definitionId: " + jobDefinitionId);
        jobLogger.info("Client id: " + clientId);
        jobLogger.info("Start with savepoint: " + options.isStartWithSavepoint());
        jobLogger.info("Restore engine job id: " + options.getRestoreEngineJobId());

        String hoconConfig = null;
        String configFile = null;
        String engineId = null;
        boolean submitted = false;
        boolean submitRequestStarted = false;

        try {
            // Inside the try: a driver that cannot be published has to fail the instance through
            // handleCoreFailure instead of leaving it RUNNING without a job log.
            hoconConfig = driverJarPublisher.publish(clientId, instance.getRuntimeConfig());

            jobLogger.info("Writing streaming config file...");
            configFile = configFileService.writeConfig(instanceId, hoconConfig);
            jobLogger.info("Streaming config file written to: " + configFile);
            jobLogger.info("Submitting streaming job via REST API...");
            log.info(
                    "Submitting streaming job to Zeta, instanceId={}, clientId={}, startWithSavepoint={}, restoreEngineJobId={}",
                    instanceId,
                    clientId,
                    options.isStartWithSavepoint(),
                    options.getRestoreEngineJobId()
            );

            String filename = "streaming-job-" + instanceId + ".conf";

            if (streamingJobResultHandler.skipSubmissionIfNotStarting(instanceId, instance.getEngineJobId())) {
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
                    options.isStartWithSavepoint(),
                    options.getRestoreEngineJobId()
            );

            engineId = extractJobId(resp);

            if (!engineId.equals(instance.getEngineJobId())) {
                cancelUnexpectedEngineJob(clientId, engineId, jobLogger);
                streamingJobResultHandler.handleFailure(
                        instanceId, new IllegalStateException("Engine returned a different jobId: " + engineId));
                return;
            }
            submitted = true;

            if (!streamingJobResultHandler.updateEngineId(instanceId, engineId)) {
                if (streamingJobResultHandler.isCancellationRequested(instanceId, engineId)
                        || !streamingJobResultHandler.isEngineJobTracked(instanceId, engineId)) {
                    cancelUnexpectedEngineJob(clientId, engineId, jobLogger);
                }
                return;
            }

            // The Engine has accepted the instance and returned its job id. The lineage
            // event is published on the Kafka bridge with the local identity only; every
            // failure is logged, never rethrown, and never delays the settled submission.
            String acceptedEngineJobId = engineId;
            try {
                openLineageJobLineageService.ifPresent(
                        lineageService -> lineageService.recordStart(instance, acceptedEngineJobId, true));
            } catch (Exception lineageError) {
                log.warn("Could not schedule OpenLineage lineage for instanceId={}", instanceId, lineageError);
            }

            log.info("Submit streaming job response received, instanceId={}, resp={}", instanceId, resp);
            jobLogger.info("Submit streaming job response received: " + resp);

            JobRuntimeContext ctx = buildRuntimeContext(instance, engineId, configFile);

            registerPostSubmitWatchers(ctx, jobLogger);

            jobLogger.info("=== Streaming Job Submit Complete ===");
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
        stop(instance, false);
    }

    public void stopWithSavepoint(JobInstanceVO instance) {
        stop(instance, true);
    }

    public void stop(JobInstanceVO instance, boolean stopWithSavepoint) {
        validateStopInstance(instance);

        Long instanceId = instance.getId();
        Long clientId = instance.getClientId();
        String engineJobId = instance.getEngineJobId();

        log.info(
                "Stopping streaming SeaTunnel job: instanceId={}, clientId={}, engineJobId={}, stopWithSavepoint={}",
                instanceId,
                clientId,
                engineJobId,
                stopWithSavepoint
        );

        Map<?, ?> resp = restClient.stopJob(clientId, engineJobId, stopWithSavepoint);

        log.info(
                "Stop streaming SeaTunnel job response: instanceId={}, clientId={}, engineJobId={}, stopWithSavepoint={}, resp={}",
                instanceId,
                clientId,
                engineJobId,
                stopWithSavepoint,
                resp
        );
    }

    private void registerPostSubmitWatchers(JobRuntimeContext ctx,
                                            JobFileLogger jobLogger) {
        boolean metricsRegistered = false;
        boolean watcherRegistered = false;

        try {
            streamingJobMetricsMonitor.register(ctx);
            metricsRegistered = true;
            jobLogger.info("Streaming metrics monitor registered");

            log.info("Streaming metrics monitor registered, instanceId={}, engineId={}",
                    ctx.getInstanceId(), ctx.getEngineId());
        } catch (Exception e) {
            jobLogger.warn("Streaming metrics monitor register failed: " + e.getMessage());

            log.warn("Streaming metrics monitor register failed, instanceId={}, engineId={}",
                    ctx.getInstanceId(), ctx.getEngineId(), e);
        }

        try {
            streamingJobResultWatcher.registerByRest(ctx);
            watcherRegistered = true;
            jobLogger.info("Streaming REST result watcher registered");
        } catch (Exception e) {
            jobLogger.warn("Streaming result watcher register failed: " + e.getMessage());

            log.warn("Streaming result watcher register failed, instanceId={}, engineId={}",
                    ctx.getInstanceId(), ctx.getEngineId(), e);
        }

        if (!metricsRegistered || !watcherRegistered) {
            jobLogger.warn(
                    "Streaming post-submit watcher registration incomplete. " +
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
        ctx.setJobType(JOB_TYPE_STREAMING);

        return ctx;
    }

    private void handleCoreFailure(JobFileLogger jobLogger,
                                   Long instanceId,
                                   Exception e) {
        jobLogger.error("Streaming job submit failed before engine accepted the job", e);

        try {
            streamingJobResultHandler.handleFailure(instanceId, e);
        } catch (Exception handlerEx) {
            log.error("Streaming handleFailure threw exception, instanceId={}", instanceId, handlerEx);
        }

        throw (e instanceof JobSubmitException)
                ? (JobSubmitException) e
                : new JobSubmitException(JobSubmitStage.SUBMIT, "Submit streaming job failed", e);
    }

    private void handleSubmitRequestFailure(JobFileLogger jobLogger,
                                            Long instanceId,
                                            Exception e) {
        if (isSubmitOutcomeAmbiguous(e)) {
            jobLogger.error("Streaming submission response unavailable; automatic reconciliation pending", e);
            throw new JobSubmitException(
                    JobSubmitStage.SUBMIT,
                    "Submit streaming job failed before an Engine job id was received",
                    e,
                    true
            );
        }

        jobLogger.error("Streaming job submission failed before an Engine job id was received", e);
        try {
            streamingJobResultHandler.handleFailure(instanceId, e);
        } catch (Exception handlerEx) {
            log.error("Record streaming submit failure failed, instanceId={}", instanceId, handlerEx);
        }

        throw new JobSubmitException(
                JobSubmitStage.SUBMIT,
                "Submit streaming job failed before an Engine job id was received",
                e
        );
    }

    private static boolean isSubmitOutcomeAmbiguous(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SeaTunnelClientException) {
                int httpStatus = ((SeaTunnelClientException) current).getHttpStatus();
                if (httpStatus >= 400 && httpStatus < 500) {
                    return false;
                }
                if (httpStatus >= 500) {
                    String body = ((SeaTunnelClientException) current).getResponseBody();
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

    private void cancelUnexpectedEngineJob(Long clientId, String engineId, JobFileLogger jobLogger) {
        try {
            restClient.stopJob(clientId, engineId, false);
            jobLogger.warn("Stopped an Engine job after its submit response conflicted with local state: " + engineId);
        } catch (Exception stopError) {
            log.warn("Could not stop the Engine job after its submit response conflicted with local state, engineJobId={}",
                    engineId, stopError);
        }
    }

    private void handlePostSubmitFailure(JobFileLogger jobLogger,
                                         Long instanceId,
                                         String engineId,
                                         Exception e) {
        jobLogger.error(
                "Streaming job was submitted to SeaTunnel Engine, but post-submit handling failed. " +
                        "instanceId=" + instanceId + ", engineId=" + engineId,
                e
        );

        log.warn("Streaming post-submit handling failed after job accepted by engine, instanceId={}, engineId={}",
                instanceId, engineId, e);
    }

    private String extractJobId(Map<?, ?> resp) {
        Object jobIdObj = resp == null ? null : resp.get("jobId");

        if (jobIdObj == null) {
            throw new IllegalStateException("REST submit response missing jobId, resp=" + resp);
        }

        return jobIdObj.toString();
    }

    private void validate(JobInstanceVO instance) {
        if (instance == null) {
            throw new IllegalArgumentException("Streaming job instance must not be null");
        }

        if (instance.getId() == null) {
            throw new IllegalArgumentException("Streaming job instance id must not be null");
        }

        if (instance.getClientId() == null || instance.getClientId() <= 0) {
            throw new IllegalArgumentException("Streaming job client id must not be null");
        }

        if (StringUtils.isBlank(instance.getEngineJobId())) {
            throw new IllegalArgumentException("Streaming job Engine id must not be blank");
        }

        if (StringUtils.isBlank(instance.getLogPath())) {
            throw new IllegalArgumentException("Streaming job log path must not be blank");
        }

        if (StringUtils.isBlank(instance.getRuntimeConfig())) {
            throw new IllegalArgumentException("Streaming job runtime config must not be blank");
        }
    }

    private void validateSubmitOptions(StreamingJobSubmitOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("Streaming submit options must not be null");
        }

        if (options.isStartWithSavepoint()
                && (options.getRestoreEngineJobId() == null)) {
            throw new IllegalArgumentException("restoreEngineJobId must be positive when startWithSavepoint is true");
        }
    }

    private void validateStopInstance(JobInstanceVO instance) {
        if (instance == null || instance.getId() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobInstance");
        }

        if (instance.getClientId() == null || instance.getClientId() <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "clientId");
        }

        if (instance.getEngineJobId() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "engineJobId");
        }
    }

    private String safeConfig(String config) {
        return config == null ? "" : config;
    }
}
