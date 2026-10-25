package org.apache.seatunnel.web.engine.client.handler;


import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.engine.client.modal.ZetaJobStatusResolveResult;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolve real SeaTunnel Zeta job status by REST API.
 *
 * <p>
 * This resolver is mainly used by SeaTunnel Web restart recovery.
 * When SeaTunnel Web restarts, in-memory watchers are lost, so local RUNNING
 * instances need to be reconciled with Zeta Engine by clientId + engineJobId.
 * </p>
 */
@Slf4j
@Component
@SuppressWarnings({"rawtypes", "unchecked"})
public class ZetaJobStatusHandler {

    private static final List<String> FINISHED_JOB_STATES = Arrays.asList(
            "FINISHED",
            "FAILED",
            "CANCELED",
            "CANCELLED",
            "SAVEPOINT_DONE",
            "UNKNOWABLE"
    );

    /**
     * Local statuses the Engine reports under their own name and that carry no explicit rule in
     * {@link #mapToLocalStatus(String)}. Every other local status is a Web-side state the Engine
     * never reports, so accepting it from the Engine would turn an unknown Engine vocabulary into
     * a final state.
     */
    private static final List<JobStatus> ENGINE_OWN_STATUSES = Arrays.asList(
            JobStatus.SAVEPOINT_DONE,
            JobStatus.UNKNOWABLE
    );

    @Resource
    private SeaTunnelRestClient seaTunnelRestClient;

    public ZetaJobStatusResolveResult resolve(Long clientId, String engineJobId) {
        if (clientId == null || clientId <= 0) {
            return ZetaJobStatusResolveResult.notFound(
                    "clientId is empty, cannot reconcile Zeta job status"
            );
        }

        if (StringUtils.isBlank(engineJobId)) {
            return ZetaJobStatusResolveResult.notFound(
                    "engineJobId is empty, cannot reconcile Zeta job status"
            );
        }

        try {
            ZetaJobStatusResolveResult runningResult =
                    resolveFromRunningJobs(clientId, engineJobId);

            if (runningResult != null) {
                return runningResult;
            }

            ZetaJobStatusResolveResult jobInfoResult =
                    resolveFromJobInfo(clientId, engineJobId);

            if (jobInfoResult != null) {
                return jobInfoResult;
            }

            ZetaJobStatusResolveResult finishedResult =
                    resolveFromFinishedJobs(clientId, engineJobId);

            if (finishedResult != null) {
                return finishedResult;
            }

            return ZetaJobStatusResolveResult.notFound(
                    "Zeta job is not found in running-jobs, job-info or finished-jobs, engineJobId="
                            + engineJobId
            );
        } catch (Exception e) {
            log.warn(
                    "Resolve Zeta job status failed, clientId={}, engineJobId={}",
                    clientId,
                    engineJobId,
                    e
            );

            /*
             * 这里不建议直接返回 FAILED。
             *
             * 因为有可能只是 Web 重启后 Zeta 临时不可访问，
             * 如果立刻标记 FAILED，可能会误伤仍在 Zeta 中运行的任务。
             *
             * 上层 Recovery 建议：
             * - UNKNOWN / UNKNOWABLE：本轮跳过，等待下一轮恢复任务再对账。
             * - 确认 FINISHED / FAILED / CANCELED 后再补偿本地状态。
             */
            return ZetaJobStatusResolveResult.unknown(
                    "Resolve Zeta job status failed, engineJobId="
                            + engineJobId
                            + ", reason="
                            + rootCauseMessage(e)
            );
        }
    }

    private ZetaJobStatusResolveResult resolveFromRunningJobs(Long clientId,
                                                              String engineJobId) {
        List runningJobs = seaTunnelRestClient.runningJobs(clientId);
        if (runningJobs == null || runningJobs.isEmpty()) {
            return null;
        }

        for (Object item : runningJobs) {
            Long jobId = extractJobId(item);
            if (!matchesJobId(jobId, engineJobId)) {
                continue;
            }

            String engineStatus = extractStatus(item);
            if (StringUtils.isBlank(engineStatus)) {
                engineStatus = "RUNNING";
            }

            JobStatus localStatus = mapToLocalStatus(engineStatus);
            if (localStatus == null) {
                // The Engine still lists the job as running but reports a status this Web does
                // not know. Treating it as final would fail a job that is still alive.
                return ZetaJobStatusResolveResult.running(engineStatus);
            }

            if (isRunningLike(localStatus)) {
                return ZetaJobStatusResolveResult.running(engineStatus);
            }

            return ZetaJobStatusResolveResult.finished(
                    localStatus,
                    engineStatus,
                    extractErrorMessage(item),
                    "Zeta job was found in running-jobs, but status is final, engineJobId="
                            + engineJobId
                            + ", engineStatus="
                            + engineStatus
            );
        }

        return null;
    }

    private ZetaJobStatusResolveResult resolveFromJobInfo(Long clientId,
                                                          String engineJobId) {
        Map jobInfo = seaTunnelRestClient.jobInfo(clientId, engineJobId);
        if (jobInfo == null || jobInfo.isEmpty()) {
            return null;
        }

        String engineStatus = readStatus(jobInfo);
        if (StringUtils.isBlank(engineStatus)) {
            return null;
        }

        JobStatus localStatus = mapToLocalStatus(engineStatus);
        if (localStatus == null) {
            return ZetaJobStatusResolveResult.unknown(
                    "Unknown Zeta job status from job-info, engineJobId="
                            + engineJobId
                            + ", engineStatus="
                            + engineStatus
            );
        }
        if (JobStatus.UNKNOWABLE.equals(localStatus)) {
            // The Engine answered and reports the job as unknowable: this is a final status, not
            // a transport failure, so the caller may reconcile it instead of retrying forever.
            return ZetaJobStatusResolveResult.finished(
                    localStatus,
                    engineStatus,
                    readErrorMsg(jobInfo),
                    "Zeta job status is UNKNOWABLE according to job-info, engineJobId="
                            + engineJobId
            );
        }

        if (isRunningLike(localStatus)) {
            return ZetaJobStatusResolveResult.running(engineStatus);
        }

        return ZetaJobStatusResolveResult.finished(
                localStatus,
                engineStatus,
                readErrorMsg(jobInfo),
                "Zeta job has finished according to job-info, engineJobId="
                        + engineJobId
                        + ", engineStatus="
                        + engineStatus
        );
    }

    private ZetaJobStatusResolveResult resolveFromFinishedJobs(Long clientId,
                                                               String engineJobId) {
        for (String state : FINISHED_JOB_STATES) {
            ZetaJobStatusResolveResult result =
                    resolveFromFinishedJobsByState(clientId, engineJobId, state);

            if (result != null) {
                return result;
            }
        }

        return null;
    }

    private ZetaJobStatusResolveResult resolveFromFinishedJobsByState(Long clientId,
                                                                      String engineJobId,
                                                                      String state) {
        // A failed history query is not evidence that the job does not exist. Let resolve()
        // return UNKNOWN so automatic recovery waits for a successful reconciliation.
        List finishedJobs = seaTunnelRestClient.finishedJobs(clientId, state);

        if (finishedJobs == null || finishedJobs.isEmpty()) {
            return null;
        }

        for (Object item : finishedJobs) {
            Long jobId = extractJobId(item);
            if (!matchesJobId(jobId, engineJobId)) {
                continue;
            }

            String engineStatus = extractStatus(item);
            if (StringUtils.isBlank(engineStatus)) {
                engineStatus = state;
            }

            JobStatus localStatus = mapToLocalStatus(engineStatus);
            if (localStatus == null || JobStatus.UNKNOWABLE.equals(localStatus)) {
                localStatus = mapToLocalStatus(state);
                engineStatus = state;
            }
            if (localStatus == null) {
                // Neither the reported status nor the queried state is known to this Web.
                return null;
            }

            if (isRunningLike(localStatus)) {
                return ZetaJobStatusResolveResult.running(engineStatus);
            }

            return ZetaJobStatusResolveResult.finished(
                    localStatus,
                    engineStatus,
                    extractErrorMessage(item),
                    "Zeta job has finished according to finished-jobs, engineJobId="
                            + engineJobId
                            + ", engineStatus="
                            + engineStatus
            );
        }

        return null;
    }

    private Long extractJobId(Object item) {
        if (item == null) {
            return null;
        }

        if (item instanceof Number) {
            return Long.valueOf(((Number) item).longValue());
        }

        if (item instanceof String) {
            return parseLong((String) item);
        }

        if (item instanceof Map) {
            Map map = (Map) item;

            Object value = firstNonNull(
                    map.get("jobId"),
                    map.get("job_id"),
                    map.get("id"),
                    map.get("engineJobId"),
                    map.get("engine_job_id")
            );

            return value == null ? null : parseLong(String.valueOf(value));
        }

        return null;
    }

    private String extractStatus(Object item) {
        if (item == null) {
            return null;
        }

        if (item instanceof Map) {
            return readStatus((Map) item);
        }

        return null;
    }

    private String extractErrorMessage(Object item) {
        if (item == null) {
            return null;
        }

        if (item instanceof Map) {
            return readErrorMsg((Map) item);
        }

        return null;
    }

    private String readStatus(Map jobInfo) {
        if (jobInfo == null || jobInfo.isEmpty()) {
            return null;
        }

        Object value = firstNonNull(
                jobInfo.get("jobStatus"),
                jobInfo.get("job_status"),
                jobInfo.get("status"),
                jobInfo.get("state"),
                jobInfo.get("jobState")
        );

        if (value == null) {
            return null;
        }

        String status = String.valueOf(value);
        if (StringUtils.isBlank(status) || "null".equalsIgnoreCase(status.trim())) {
            return null;
        }

        return status.trim();
    }

    private String readErrorMsg(Map jobInfo) {
        if (jobInfo == null || jobInfo.isEmpty()) {
            return null;
        }

        Object value = firstNonNull(
                jobInfo.get("errorMsg"),
                jobInfo.get("error_msg"),
                jobInfo.get("errorMessage"),
                jobInfo.get("message"),
                jobInfo.get("exception"),
                jobInfo.get("cause")
        );

        if (value == null) {
            return null;
        }

        String message = String.valueOf(value);
        return StringUtils.isBlank(message) ? null : message;
    }

    /**
     * Maps an Engine status onto a local status.
     *
     * @return the local status, or {@code null} when the Engine reports a status this Web does
     *     not know. An unknown status is not evidence of a final state: the Engine vocabulary
     *     grows over time, so an unknown status must leave the instance running instead of
     *     failing a job that is still alive.
     */
    private JobStatus mapToLocalStatus(String engineStatus) {
        if (StringUtils.isBlank(engineStatus)) {
            return null;
        }

        String normalized = engineStatus.trim().toUpperCase();

        if ("INITIALIZING".equals(normalized)
                || "CREATED".equals(normalized)
                || "PENDING".equals(normalized)
                || "SCHEDULED".equals(normalized)
                || "RUNNING".equals(normalized)
                || "FAILING".equals(normalized)
                || "DOING_SAVEPOINT".equals(normalized)
                || "CANCELING".equals(normalized)
                || "CANCELLING".equals(normalized)
                || "STOPPING".equals(normalized)) {
            return JobStatus.RUNNING;
        }

        if ("FINISHED".equals(normalized)
                || "SUCCESS".equals(normalized)
                || "SUCCEEDED".equals(normalized)
                || "DONE".equals(normalized)
                || "COMPLETED".equals(normalized)) {
            return JobStatus.FINISHED;
        }

        if ("FAILED".equals(normalized)
                || "FAILURE".equals(normalized)) {
            return JobStatus.FAILED;
        }

        if ("CANCELED".equals(normalized)
                || "CANCELLED".equals(normalized)
                || "STOPPED".equals(normalized)) {
            return JobStatus.CANCELED;
        }

        try {
            JobStatus parsed = JobStatus.valueOf(normalized);
            if (ENGINE_OWN_STATUSES.contains(parsed)) {
                return parsed;
            }
        } catch (Exception e) {
            log.warn("Unknown Zeta job status: {}", engineStatus);
            return null;
        }

        log.warn("Unknown Zeta job status: {}", engineStatus);
        return null;
    }

    private boolean isRunningLike(JobStatus status) {
        if (status == null) {
            return false;
        }

        return JobStatus.RUNNING.equals(status)
                || JobStatus.INITIALIZING.equals(status)
                || JobStatus.CREATED.equals(status)
                || JobStatus.PENDING.equals(status)
                || JobStatus.SCHEDULED.equals(status)
                || JobStatus.FAILING.equals(status)
                || JobStatus.DOING_SAVEPOINT.equals(status)
                || JobStatus.CANCELING.equals(status);
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

    private Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }

        try {
            return Long.valueOf(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean matchesJobId(Long jobId, String engineJobId) {
        return jobId != null && Objects.equals(jobId, parseLong(engineJobId));
    }

    private String rootCauseMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }

        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String message = root.getMessage();
        if (StringUtils.isBlank(message)) {
            return root.getClass().getSimpleName();
        }

        return root.getClass().getSimpleName() + ": " + message;
    }
}
