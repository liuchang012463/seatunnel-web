package org.apache.seatunnel.web.api.metrics;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.api.utils.RedactedErrors;
import org.apache.seatunnel.web.common.utils.EngineJobStatusParser;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watch SeaTunnel batch job result by REST polling.
 *
 * <p>
 * This watcher only detects the final Zeta job status.
 * Local status update and final metrics persistence are delegated to
 * JobResultHandler.
 * </p>
 */
@Component
@Slf4j
public class JobResultWatcher {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Resource
    private JobResultHandler resultHandler;

    @Resource
    private SeaTunnelRestClient seatunnelRestClient;

    @Value("${seatunnel.result.poll-interval-ms}")
    private long pollIntervalMs;

    @Value("${seatunnel.result.poll-timeout-ms}")
    private long pollTimeoutMs;

    /**
     * Consecutive unknown-status polls one watcher tolerates before it gives up. Giving up is
     * safe: the instance stays running-like and the recovery scheduler reconciles it as soon as
     * the Engine reports a status this web knows (or stops answering).
     */
    @Value("${seatunnel.result.unknown-status-poll-limit:150}")
    private int unknownStatusPollLimit;

    public void registerByRest(final JobRuntimeContext context) {
        if (context == null) {
            log.warn("Skip registering REST job result watcher because context is null");
            return;
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                watch(context);
            }
        });
    }

    private void watch(JobRuntimeContext context) {
        long start = System.currentTimeMillis();

        Long instanceId = context.getInstanceId();
        Long clientId = context.getClientId();
        String engineId = context.getEngineId();
        int unknownStatusPolls = 0;

        try {
            validateContext(context);

            while (true) {
                checkTimeout(start, engineId);

                Map jobInfo = seatunnelRestClient.jobInfo(clientId, engineId);
                String statusStr = readStatus(jobInfo);

                if (StringUtils.isBlank(statusStr)) {
                    log.warn(
                            "Zeta job-info returned no status, instanceId={}, clientId={}, engineId={}, resp={}",
                            instanceId,
                            clientId,
                            engineId,
                            jobInfo
                    );

                    sleepQuietly();
                    continue;
                }

                JobStatus status = parseJobStatus(statusStr);
                if (status == null) {
                    // The Engine reports a status this web does not know. The job may still be
                    // running, so it keeps being polled instead of being failed — but only for a
                    // bounded streak, otherwise a permanently unknown status pins this thread.
                    unknownStatusPolls++;
                    if (unknownStatusPolls >= unknownStatusPollLimit) {
                        log.warn(
                                "Zeta job status stayed unknown for {} polls, releasing the watcher; "
                                        + "the recovery scheduler reconciles the instance, instanceId={}, "
                                        + "clientId={}, engineId={}, engineStatus={}",
                                unknownStatusPollLimit,
                                instanceId,
                                clientId,
                                engineId,
                                statusStr
                        );
                        return;
                    }
                    log.warn(
                            "Unknown Zeta job status, instanceId={}, clientId={}, engineId={}, engineStatus={}",
                            instanceId,
                            clientId,
                            engineId,
                            statusStr
                    );

                    sleepQuietly();
                    continue;
                }
                unknownStatusPolls = 0;

                log.debug(
                        "Polling Zeta job status, instanceId={}, clientId={}, engineId={}, engineStatus={}, localStatus={}",
                        instanceId,
                        clientId,
                        engineId,
                        statusStr,
                        status
                );

                if (!status.isEndState()) {
                    sleepQuietly();
                    continue;
                }

                handleFinalStatus(instanceId, status, jobInfo);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            log.warn(
                    "REST job result watcher interrupted, instanceId={}, clientId={}, engineId={}, errorType={}, error={}",
                    instanceId,
                    clientId,
                    engineId,
                    RedactedErrors.typeOf(e),
                    RedactedErrors.mask(e.getMessage()),
                    RedactedErrors.redacted(e, RedactedErrors.mask(e.getMessage()))
            );

            resultHandler.handleFailure(instanceId, e);
        } catch (Exception e) {
            log.warn(
                    "REST job result watcher failed, instanceId={}, clientId={}, engineId={}, errorType={}, error={}",
                    instanceId,
                    clientId,
                    engineId,
                    RedactedErrors.typeOf(e),
                    RedactedErrors.mask(e.getMessage()),
                    RedactedErrors.redacted(e, RedactedErrors.mask(e.getMessage()))
            );

            resultHandler.handleFailure(instanceId, e);
        } finally {
            log.info(
                    "REST job result watcher finished, instanceId={}, clientId={}, engineId={}",
                    instanceId,
                    clientId,
                    engineId
            );
        }
    }

    private void handleFinalStatus(Long instanceId,
                                   JobStatus status,
                                   Map jobInfo) {
        String errorMessage = JobStatus.FINISHED.equals(status)
                ? null
                : readErrorMsg(jobInfo);

        resultHandler.handleFinalStatus(instanceId, status, errorMessage);
    }

    private void validateContext(JobRuntimeContext context) {
        if (context.getInstanceId() == null || context.getInstanceId() <= 0) {
            throw new IllegalArgumentException("instanceId must not be null");
        }

        if (context.getClientId() == null || context.getClientId() <= 0) {
            throw new IllegalArgumentException("clientId must not be null");
        }

        if (context.getEngineId() == null) {
            throw new IllegalArgumentException("engineId must not be null");
        }
    }

    private void checkTimeout(long start, String engineId) {
        if (pollTimeoutMs <= 0) {
            return;
        }

        long cost = System.currentTimeMillis() - start;
        if (cost > pollTimeoutMs) {
            throw new IllegalStateException("Polling job-info timeout, engineId=" + engineId);
        }
    }

    private String readStatus(Map jobInfo) {
        if (jobInfo == null || jobInfo.isEmpty()) {
            return null;
        }

        Object status = firstNonNull(
                jobInfo.get("jobStatus"),
                jobInfo.get("job_status"),
                jobInfo.get("status"),
                jobInfo.get("state"),
                jobInfo.get("jobState")
        );

        if (status == null) {
            return null;
        }

        String value = String.valueOf(status);
        if (StringUtils.isBlank(value) || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }

        return value.trim();
    }

    private String readErrorMsg(Map jobInfo) {
        if (jobInfo == null || jobInfo.isEmpty()) {
            return null;
        }

        Object errorMsg = firstNonNull(
                jobInfo.get("errorMsg"),
                jobInfo.get("error_msg"),
                jobInfo.get("errorMessage"),
                jobInfo.get("message"),
                jobInfo.get("exception"),
                jobInfo.get("cause")
        );

        if (errorMsg == null) {
            return null;
        }

        String value = String.valueOf(errorMsg);
        if (StringUtils.isBlank(value) || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }

        return value.trim();
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }

        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private void sleepQuietly() throws InterruptedException {
        Thread.sleep(pollIntervalMs);
    }

    /**
     * The local status of the Engine status.
     *
     * @return the local status, or {@code null} when the Engine reports a status this web does
     *     not know. An unknown status is not evidence of a final state, so the job keeps being
     *     polled instead of being failed.
     */
    private JobStatus parseJobStatus(String value) {
        JobStatus status = EngineJobStatusParser.parse(value);
        if (status == null && StringUtils.isNotBlank(value)) {
            log.warn("Unknown Zeta job status: {}", value);
        }
        return status;
    }
}
