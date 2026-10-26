package org.apache.seatunnel.web.api.metrics.streaming;

import org.apache.commons.lang3.StringUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metrics.JobRuntimeContext;
import org.apache.seatunnel.web.common.enums.JobResult;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.utils.EngineJobStatusParser;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.apache.seatunnel.web.api.utils.RedactedErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class StreamingJobResultWatcher {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Resource
    private StreamingJobResultHandler streamingJobResultHandler;

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

    public void registerByRest(JobRuntimeContext context) {
        executor.submit(() -> watch(context));
    }

    private void watch(JobRuntimeContext context) {
        long start = System.currentTimeMillis();

        Long instanceId = context.getInstanceId();
        String engineId = context.getEngineId();
        int unknownStatusPolls = 0;

        try {
            while (true) {
                checkTimeout(start, engineId);

                Map jobInfo = seatunnelRestClient.jobInfo(context.getClientId(), engineId);
                String statusStr = readStatus(jobInfo);

                if (statusStr == null) {
                    log.warn("Streaming job-info returned no status, instanceId={}, engineId={}, resp={}",
                            instanceId, engineId, jobInfo);
                    sleepQuietly();
                    continue;
                }

                JobStatus status = parseJobStatus(statusStr);

                if (status == null) {
                    // Unknown is not evidence of a final state, so the job keeps being polled
                    // instead of being failed — but only for a bounded streak, otherwise a
                    // permanently unknown status pins this thread.
                    unknownStatusPolls++;
                    if (unknownStatusPolls >= unknownStatusPollLimit) {
                        log.warn("Streaming job status stayed unknown for {} polls, releasing the "
                                        + "watcher; the recovery scheduler reconciles the instance, "
                                        + "instanceId={}, engineId={}, status={}",
                                unknownStatusPollLimit, instanceId, engineId, statusStr);
                        return;
                    }
                    log.warn("Unknown streaming job status, instanceId={}, engineId={}, status={}, resp={}",
                            instanceId, engineId, statusStr, jobInfo);
                    sleepQuietly();
                    continue;
                }
                unknownStatusPolls = 0;

                if (isRunningStatus(status)) {
                    sleepQuietly();
                    continue;
                }

                handleFinalStatus(instanceId, status, jobInfo);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Streaming REST job result watcher interrupted, instanceId={}, engineId={}, "
                            + "errorType={}, error={}",
                    instanceId, engineId,
                    RedactedErrors.typeOf(e),
                    RedactedErrors.mask(e.getMessage()),
                    RedactedErrors.redacted(e, RedactedErrors.mask(e.getMessage())));
        } catch (Exception e) {
            log.warn("Streaming REST job result watcher failed, instanceId={}, engineId={}, "
                            + "errorType={}, error={}",
                    instanceId, engineId,
                    RedactedErrors.typeOf(e),
                    RedactedErrors.mask(e.getMessage()),
                    RedactedErrors.redacted(e, RedactedErrors.mask(e.getMessage())));

            streamingJobResultHandler.handleFailure(instanceId, e);
        } finally {
            log.info("Streaming REST job result watcher finished, instanceId={}, engineId={}",
                    instanceId, engineId);
        }
    }

    private void handleFinalStatus(Long instanceId,
                                   JobStatus status,
                                   Map jobInfo) {
        if (status == JobStatus.FINISHED) {
            streamingJobResultHandler.handleSuccess(instanceId);
            return;
        }

        if (status == JobStatus.CANCELED) {
            streamingJobResultHandler.handleCanceled(instanceId, readErrorMsg(jobInfo));
            return;
        }

        JobResult jr = new JobResult(JobStatus.FAILED);
        jr.setStatus(status);
        jr.setError(readErrorMsg(jobInfo));

        streamingJobResultHandler.handleFailure(instanceId, jr);
    }

    private boolean isRunningStatus(JobStatus status) {
        return status == JobStatus.RUNNING
                || status == JobStatus.INITIALIZING
                || status == JobStatus.CREATED
                || status == JobStatus.PENDING
                || status == JobStatus.SCHEDULED;
    }

    private void checkTimeout(long start, String engineId) {
        if (pollTimeoutMs <= 0) {
            return;
        }

        long cost = System.currentTimeMillis() - start;
        if (cost > pollTimeoutMs) {
            throw new IllegalStateException("Polling streaming job-info timeout, engineId=" + engineId);
        }
    }

    private String readStatus(Map jobInfo) {
        if (jobInfo == null) {
            return null;
        }

        Object status = jobInfo.get("jobStatus");
        if (status == null) {
            return null;
        }

        String value = String.valueOf(status);
        if (value.trim().isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }

        return value.trim();
    }

    private String readErrorMsg(Map jobInfo) {
        if (jobInfo == null) {
            return null;
        }

        Object errorMsg = jobInfo.get("errorMsg");
        return errorMsg == null ? null : String.valueOf(errorMsg);
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
            log.warn("Unknown streaming job status: {}", value);
        }
        return status;
    }
}
