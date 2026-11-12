package org.apache.seatunnel.web.api.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageJobLineageService;
import org.apache.seatunnel.web.api.utils.HoconSensitiveMaskUtil;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String ENGINE_SERVER_LOG_FILE = "seatunnel.log";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Pattern ENGINE_LOG_ENTRY_HEADER = Pattern.compile(
            "(?m)^.*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:[,\\.]\\d{1,9})?\\s+"
                    + "(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\b"
    );

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
        jobLogger.info("引接引擎 ID: " + clientId);

        String hoconConfig = null;
        String configFile = null;
        String engineId = null;
        boolean submitted = false;
        boolean submitRequestStarted = false;

        try {
            // A driver publication failure must be handled as a local submission failure instead
            // of leaving the instance STARTING without a job log.
            // SQLite files stay in object storage; DuckDB httpfs reads them from the Engine.
            hoconConfig = instance.getRuntimeConfig();
            hoconConfig = driverJarPublisher.publish(clientId, hoconConfig);

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
                handleSubmitRequestFailure(jobLogger, instanceId, clientId, e);
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
                                            Long clientId,
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
        appendEngineServerErrorLog(jobLogger, instanceId, clientId, e);
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
     * Engine 3.0 returns only the top-level message for factory/catalog errors.
     * The actual cause is logged by ExceptionHandlingFilter in the node log,
     * because no per-job log exists until submission succeeds.
     */
    private void appendEngineServerErrorLog(JobFileLogger jobLogger,
                                            Long instanceId,
                                            Long clientId,
                                            Throwable error) {
        SeaTunnelClientException engineException = findClientException(error);
        if (engineException == null || engineException.getHttpStatus() < 500) {
            return;
        }

        String responseBody = engineException.getResponseBody();
        boolean factoryRejected = responseBody != null
                && responseBody.contains("ErrorCode:[API-06]")
                && responseBody.contains("Factory initialize failed");
        boolean catalogRejected = responseBody != null
                && responseBody.contains("COMMON-21")
                && responseBody.contains("unsupported get catalog table");
        if (!factoryRejected && !catalogRejected) {
            return;
        }

        try {
            String engineLogs = restClient.clusterLogFile(clientId, ENGINE_SERVER_LOG_FILE);
            String matchingSection = extractMatchingEngineError(engineLogs, responseBody);
            if (StringUtils.isBlank(matchingSection)) {
                log.warn("Engine rejected batch submission but no matching node stack trace was found, "
                        + "instanceId={}, httpStatus={}", instanceId, engineException.getHttpStatus());
                jobLogger.warn("Engine did not expose a matching server stack trace through "
                        + "GET /logs/seatunnel.log");
                return;
            }

            String safeSection = HoconSensitiveMaskUtil.maskSensitiveText(matchingSection);
            jobLogger.info("=== SeaTunnel Engine server exception (seatunnel.log) ===\n"
                    + safeSection + "\n=== End SeaTunnel Engine server exception ===");
            log.info("Attached matching SeaTunnel Engine stack trace to batch log, instanceId={}", instanceId);
        } catch (Exception fetchError) {
            log.warn("Could not fetch SeaTunnel Engine server exception log, instanceId={}, errorType={}",
                    instanceId, fetchError.getClass().getSimpleName());
            jobLogger.warn("Could not fetch the matching Engine stack trace from "
                    + "GET /logs/seatunnel.log; check the SeaTunnel Engine node log");
        }
    }

    private SeaTunnelClientException findClientException(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SeaTunnelClientException) {
                return (SeaTunnelClientException) current;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    private String extractMatchingEngineError(String engineLogs, String responseBody) {
        if (StringUtils.isBlank(engineLogs) || StringUtils.isBlank(responseBody)) {
            return "";
        }

        String errorMessage = responseMessage(responseBody);
        if (StringUtils.isBlank(errorMessage)) {
            return "";
        }

        StringBuilder logText = new StringBuilder();
        try {
            appendTextValues(JSON_MAPPER.readTree(engineLogs), logText);
        } catch (Exception ignored) {
            logText.append(engineLogs);
        }

        int errorIndex = logText.lastIndexOf(errorMessage);
        if (errorIndex < 0) {
            return "";
        }

        int sectionStart = 0;
        int sectionEnd = logText.length();
        Matcher headerMatcher = ENGINE_LOG_ENTRY_HEADER.matcher(logText);
        while (headerMatcher.find()) {
            if (headerMatcher.start() <= errorIndex) {
                sectionStart = headerMatcher.start();
            } else {
                sectionEnd = headerMatcher.start();
                break;
            }
        }

        String section = logText.substring(sectionStart, sectionEnd).trim();
        return section.contains("ExceptionHandlingFilter")
                && section.contains("at org.apache.seatunnel.")
                ? section
                : "";
    }

    private String responseMessage(String responseBody) {
        try {
            JsonNode message = JSON_MAPPER.readTree(responseBody).path("message");
            if (message.isTextual() && StringUtils.isNotBlank(message.asText())) {
                return message.asText();
            }
        } catch (Exception ignored) {
            // Older Engine responses may not be JSON; search their raw body instead.
        }
        return responseBody.trim();
    }

    private void appendTextValues(JsonNode node, StringBuilder target) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            target.append(node.asText()).append(System.lineSeparator());
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> appendTextValues(child, target));
        }
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
