package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineRun;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.Date;
import java.util.List;

/** Refreshes only the local latest-run cache from OpenMetadata PipelineStatus. */
@Slf4j
@Service
public class MetadataStatusSynchronizer {

    private final MetadataBindingDao metadataBindingDao;
    private final OpenMetadataClient openMetadataClient;
    private final MetadataStatusProperties properties;
    private final MetadataPipelineOperationService operationService;
    /** Injected when the shared cache is active; tests fall back to pass-through. */
    private volatile OmReadCache omReadCache = OmReadCache.disabled();

    /** Optional to keep existing unit-test constructors and lightweight deployments compatible. */
    @Autowired(required = false)
    private MetadataInventoryCache metadataInventoryCache;

    /** Bounds the re-triggering of a scan OpenMetadata never registered. */
    @Autowired(required = false)
    private MetadataReconcileProperties reconcileProperties;

    private int maxScanTriggerRetries() {
        return reconcileProperties == null ? 4 : reconcileProperties.getMaxRetryCount();
    }

    @Autowired(required = false)
    void setOmReadCache(OmReadCache omReadCache) {
        if (omReadCache != null) {
            this.omReadCache = omReadCache;
        }
    }

    @Autowired(required = false)
    void setMetadataInventoryCache(MetadataInventoryCache metadataInventoryCache) {
        this.metadataInventoryCache = metadataInventoryCache;
    }

    public MetadataStatusSynchronizer(
            MetadataBindingDao metadataBindingDao,
            OpenMetadataClient openMetadataClient,
            MetadataStatusProperties properties,
            MetadataPipelineOperationService operationService) {
        this.metadataBindingDao = metadataBindingDao;
        this.openMetadataClient = openMetadataClient;
        this.properties = properties;
        this.operationService = operationService;
    }

    public void refreshStatuses() {
        Date now = new Date();
        Date olderThan = new Date(now.getTime() - properties.getIntervalMs());
        for (MetadataSourceBinding candidate : metadataBindingDao.queryStatusRefreshCandidates(
                olderThan, properties.getBatchSize())) {
            refreshOne(candidate, now);
        }
    }

    void refreshOne(MetadataSourceBinding candidate, Date now) {
        if (candidate == null || candidate.getId() == null || candidate.getVersion() == null) {
            return;
        }
        if (!requiresRefresh(candidate, now)) {
            return;
        }
        try {
            openMetadataClient.assertFixedVersion();
            List<OpenMetadataPipelineRun> scanRuns = listRuns(candidate.getOmMetadataPipelineFqn());
            List<OpenMetadataPipelineRun> profileRuns = listRuns(candidate.getOmProfilerPipelineFqn());
            List<OpenMetadataPipelineRun> sampleRuns = candidate.getOmProfilerPipelineFqn() == null
                    || candidate.getOmProfilerPipelineFqn().isBlank()
                    ? List.of()
                    : listRuns(MetadataStableName.autoClassificationPipelineFqn(candidate.getDataSourceId()));
            MetadataSourceBinding latest = metadataBindingDao.queryById(candidate.getId());
            if (!owned(latest, candidate.getVersion())) {
                return;
            }
            MetadataRunStatus scanStatusBefore = latest.getScanStatus();
            Date scanSuccessBefore = latest.getScanLastSuccessTime();
            MetadataRunStatus profileStatusBefore = latest.getProfileStatus();
            Date profileSuccessBefore = latest.getProfileLastSuccessTime();
            OpenMetadataPipelineRun latestScanRun = latestRun(scanRuns);
            applyRun(latest, true, latestScanRun, now);
            applyExplorationRuns(latest, profileRuns, sampleRuns, now);
            boolean scanChanged = runOutcomeChanged(scanStatusBefore, scanSuccessBefore,
                    latest.getScanStatus(), latest.getScanLastSuccessTime());
            boolean profileChanged = runOutcomeChanged(profileStatusBefore, profileSuccessBefore,
                    latest.getProfileStatus(), latest.getProfileLastSuccessTime());
            latest.setLastStatusRefreshTime(now);
            latest.setStatusRefreshError(null);
            // Decide before the update so the marker is written with the same version, and
            // trigger after it so only the node that won the update runs the sample agent.
            boolean collectStorageSamples = storageSampleCollectionDue(latest, latestScanRun);
            if (collectStorageSamples) {
                latest.setStorageSampleScanRunId(latestScanRun.runId());
            }
            long version = latest.getVersion();
            latest.setVersion(version + 1L);
            latest.initUpdate();
            if (metadataBindingDao.updateIfVersion(latest, version)) {
                if (scanChanged || profileChanged) {
                    // A finished run changes the catalog; idle refreshes that
                    // observe no state change must not drop warm caches.
                    omReadCache.invalidateService(serviceFqnOf(latest), scanChanged, profileChanged);
                    if (metadataInventoryCache != null) {
                        metadataInventoryCache.invalidateAllSnapshots();
                    }
                }
                operationService.triggerPendingMetadataScan(latest);
                if (collectStorageSamples) {
                    operationService.triggerStorageSampleCollection(latest);
                }
            }
        } catch (Exception e) {
            markUnknown(candidate, now);
        }
    }

    /**
     * True when the latest successful scan has not been sampled for yet.
     *
     * <p>The scan run id is the marker, not the success time: the binding keeps timestamps
     * at second precision while OpenMetadata reports milliseconds, and the recomputed
     * success time was observed to drift by a second between refreshes, which made every
     * refresh look like a new scan.</p>
     */
    private static boolean storageSampleCollectionDue(
            MetadataSourceBinding binding, OpenMetadataPipelineRun latestScanRun) {
        if (!Boolean.TRUE.equals(binding.getSampleDataEnabled())
                || binding.getScanStatus() != MetadataRunStatus.SUCCESS
                || latestScanRun == null
                || latestScanRun.runId() == null
                || latestScanRun.runId().isBlank()) {
            return false;
        }
        return !latestScanRun.runId().equals(binding.getStorageSampleScanRunId());
    }

    private static boolean runOutcomeChanged(
            MetadataRunStatus statusBefore, Date successBefore,
            MetadataRunStatus statusAfter, Date successAfter) {
        if (statusBefore == null ? statusAfter != null : !statusBefore.equals(statusAfter)) {
            return true;
        }
        return successBefore == null
                ? successAfter != null
                : !successBefore.equals(successAfter);
    }

    private String serviceFqnOf(MetadataSourceBinding binding) {
        String configured = binding.getOmServiceFqn();
        return configured == null || configured.isBlank()
                ? MetadataStableName.serviceFqn(binding.getDataSourceId())
                : configured;
    }

    private void markUnknown(MetadataSourceBinding candidate, Date now) {
        MetadataSourceBinding latest = metadataBindingDao.queryById(candidate.getId());
        if (!owned(latest, candidate.getVersion())) {
            return;
        }
        // Do not turn an unavailable OpenMetadata endpoint into a false execution failure.
        // Only a currently active run loses its known state; completed/never states remain useful.
        if (MetadataPipelineOperationService.isRunning(latest.getScanStatus())) {
            latest.setScanStatus(MetadataRunStatus.UNKNOWN);
        }
        if (MetadataPipelineOperationService.isRunning(latest.getProfileStatus())
                && latest.getProfileStatus() != MetadataRunStatus.QUEUED) {
            latest.setProfileStatus(MetadataRunStatus.UNKNOWN);
        }
        latest.setLastStatusRefreshTime(now);
        latest.setStatusRefreshError(MetadataErrorCode.OM_PIPELINE_STATUS_ERROR.name());
        long version = latest.getVersion();
        latest.setVersion(version + 1L);
        latest.initUpdate();
        metadataBindingDao.updateIfVersion(latest, version);
        log.warn("Metadata status refresh failed: dataSourceId={}, code={}",
                candidate.getDataSourceId(), MetadataErrorCode.OM_PIPELINE_STATUS_ERROR);
    }

    private boolean requiresRefresh(MetadataSourceBinding binding, Date now) {
        if (MetadataPipelineOperationService.isRunning(binding.getScanStatus())
                || MetadataPipelineOperationService.isRunning(binding.getProfileStatus())
                || (binding.getProfileStatus() == MetadataRunStatus.UNKNOWN
                        && binding.getProfileLastRunTime() != null)) {
            return true;
        }
        if (binding.getLastStatusRefreshTime() == null) {
            return true;
        }
        return now.getTime() - binding.getLastStatusRefreshTime().getTime()
                >= properties.getIdleRefreshSeconds() * 1000L;
    }

    private List<OpenMetadataPipelineRun> listRuns(String fqn) {
        return fqn == null || fqn.isBlank() ? List.of() : openMetadataClient.listIngestionPipelineRuns(fqn, 1);
    }

    private void applyExplorationRuns(
            MetadataSourceBinding binding,
            List<OpenMetadataPipelineRun> profilerRuns,
            List<OpenMetadataPipelineRun> sampleRuns,
            Date now) {
        MetadataRunStatus currentStatus = binding.getProfileStatus();
        Date reservationTime = binding.getProfileLastRunTime();
        boolean activeReservation = MetadataPipelineOperationService.isRunning(currentStatus)
                || (currentStatus == MetadataRunStatus.UNKNOWN && reservationTime != null);
        if (!activeReservation || reservationTime == null) {
            // Preserve prior terminal results. Legacy rows without a local reservation
            // still learn their initial status from the profiler history.
            if (currentStatus == MetadataRunStatus.NEVER) {
                if (hasLocalFailureEvidence(binding, false)) {
                    binding.setProfileStatus(MetadataRunStatus.FAILED);
                } else if (reservationTime == null) {
                    applyRun(binding, false, latestRun(profilerRuns), now);
                }
            }
            return;
        }

        // Rows created before sampling was added have a null baseline flag. Keep
        // their profiler-only completion behavior while new reservations wait
        // until both pre-trigger run IDs have been stored.
        if (binding.getProfileRunBaselineCaptured() == null) {
            applyLegacyProfilerRun(binding, profilerRuns, reservationTime, now);
            return;
        }
        if (!binding.getProfileRunBaselineCaptured()) {
            if (now.getTime() - reservationTime.getTime()
                    >= properties.getExplorationPreparationTimeoutSeconds() * 1000L) {
                binding.setProfileStatus(MetadataRunStatus.FAILED);
                binding.setProfileLastError(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name());
            }
            return;
        }

        if (currentStatus == MetadataRunStatus.QUEUED) {
            // The async worker still owns the initial calls and any bounded trigger retries.
            // Do not let a slow SDK request race the shorter post-trigger run-registration grace.
            boolean profilerRunExists = matchingReservationRun(
                    profilerRuns, reservationTime, binding.getProfileProfilerRunIdBaseline()) != null;
            boolean sampleRunExists = matchingReservationRun(
                    sampleRuns, reservationTime, binding.getProfileSampleRunIdBaseline()) != null;
            if (!profilerRunExists || !sampleRunExists) {
                if (now.getTime() - reservationTime.getTime()
                        >= properties.getExplorationPreparationTimeoutSeconds() * 1000L) {
                    binding.setProfileStatus(MetadataRunStatus.FAILED);
                    binding.setProfileLastError(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name());
                }
                return;
            }
        }

        OpenMetadataPipelineRun profiler = matchingReservationRun(
                profilerRuns, reservationTime, binding.getProfileProfilerRunIdBaseline());
        OpenMetadataPipelineRun samples = matchingReservationRun(
                sampleRuns, reservationTime, binding.getProfileSampleRunIdBaseline());
        MetadataRunStatus profilerStatus = profiler == null
                ? null
                : OpenMetadataRunStatusMapper.fromPipelineState(profiler.pipelineState());
        MetadataRunStatus sampleStatus = samples == null
                ? null
                : OpenMetadataRunStatusMapper.fromPipelineState(samples.pipelineState());

        if (profilerStatus == MetadataRunStatus.FAILED || sampleStatus == MetadataRunStatus.FAILED) {
            binding.setProfileStatus(MetadataRunStatus.FAILED);
            binding.setProfileLastError(MetadataErrorCode.PIPELINE_EXECUTION_ERROR.name());
            return;
        }
        if (profilerStatus == MetadataRunStatus.SUCCESS && sampleStatus == MetadataRunStatus.SUCCESS) {
            binding.setProfileStatus(MetadataRunStatus.SUCCESS);
            Date profilerSuccess = runSuccessTime(profiler);
            Date sampleSuccess = runSuccessTime(samples);
            binding.setProfileLastSuccessTime(latest(profilerSuccess, sampleSuccess));
            binding.setProfileLastError(null);
            return;
        }
        if (MetadataPipelineOperationService.isRunning(profilerStatus)
                || MetadataPipelineOperationService.isRunning(sampleStatus)) {
            binding.setProfileStatus(MetadataRunStatus.RUNNING);
            return;
        }

        boolean missingRun = profiler == null || samples == null;
        Date baselineCapturedAt = binding.getProfileRunBaselineCapturedAt();
        Date triggerWaitStartedAt = baselineCapturedAt == null ? reservationTime : baselineCapturedAt;
        long ageMillis = now.getTime() - triggerWaitStartedAt.getTime();
        if (missingRun && ageMillis < properties.getTriggerGraceSeconds() * 1000L) {
            // Keep the reservation open while OM registers the second pipeline run.
            if (profiler != null || samples != null) {
                binding.setProfileStatus(MetadataRunStatus.RUNNING);
            }
            return;
        }
        if (missingRun) {
            binding.setProfileStatus(MetadataRunStatus.FAILED);
            binding.setProfileLastError(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name());
            return;
        }

        // Both runs are present but OM returned an unrecognized terminal state.
        binding.setProfileStatus(MetadataRunStatus.UNKNOWN);
    }

    private void applyLegacyProfilerRun(
            MetadataSourceBinding binding,
            List<OpenMetadataPipelineRun> profilerRuns,
            Date reservationTime,
            Date now) {
        OpenMetadataPipelineRun profiler = matchingReservationRun(profilerRuns, reservationTime, null);
        MetadataRunStatus status = profiler == null
                ? null
                : OpenMetadataRunStatusMapper.fromPipelineState(profiler.pipelineState());
        if (status == MetadataRunStatus.FAILED) {
            binding.setProfileStatus(MetadataRunStatus.FAILED);
            binding.setProfileLastError(MetadataErrorCode.PIPELINE_EXECUTION_ERROR.name());
        } else if (status == MetadataRunStatus.SUCCESS) {
            binding.setProfileStatus(MetadataRunStatus.SUCCESS);
            binding.setProfileLastSuccessTime(runSuccessTime(profiler));
            binding.setProfileLastError(null);
        } else if (MetadataPipelineOperationService.isRunning(status)) {
            binding.setProfileStatus(MetadataRunStatus.RUNNING);
        } else if (profiler == null
                && now.getTime() - reservationTime.getTime()
                        >= properties.getTriggerGraceSeconds() * 1000L) {
            binding.setProfileStatus(MetadataRunStatus.FAILED);
            binding.setProfileLastError(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name());
        }
    }

    private static OpenMetadataPipelineRun matchingReservationRun(
            List<OpenMetadataPipelineRun> runs, Date reservationTime, String baselineRunId) {
        return runs.stream()
                .filter(run -> {
                    Date runTime = runTime(run);
                    return runTime != null
                            && runTime.getTime() >= reservationTime.getTime() - 5_000L
                            && (baselineRunId == null || !baselineRunId.equals(run.runId()));
                })
                .max(Comparator.comparing(MetadataStatusSynchronizer::executionTimestamp))
                .orElse(null);
    }

    private static Date runTime(OpenMetadataPipelineRun run) {
        return MetadataPipelineOperationService.fromOmTimestamp(
                run.startDate() == null ? run.timestamp() : run.startDate());
    }

    private static Date runSuccessTime(OpenMetadataPipelineRun run) {
        return MetadataPipelineOperationService.fromOmTimestamp(
                run.endDate() == null
                        ? run.startDate() == null ? run.timestamp() : run.startDate()
                        : run.endDate());
    }

    private static Date latest(Date first, Date second) {
        if (first == null) {
            return second;
        }
        if (second == null || first.after(second)) {
            return first;
        }
        return second;
    }

    private static boolean owned(MetadataSourceBinding binding, Long expectedVersion) {
        return binding != null && expectedVersion.equals(binding.getVersion());
    }

    private static OpenMetadataPipelineRun latestRun(List<OpenMetadataPipelineRun> runs) {
        return runs.stream().max(Comparator.comparing(run -> executionTimestamp(run))).orElse(null);
    }

    /**
     * Ordering key for the runs of one pipeline. Both fields carry epoch milliseconds in the 2.0.4
     * contract; startDate is preferred because the local reservation is compared against it.
     */
    private static long executionTimestamp(OpenMetadataPipelineRun run) {
        if (run.startDate() != null) {
            return run.startDate();
        }
        return run.timestamp() == null ? 0L : run.timestamp();
    }
    private void applyRun(MetadataSourceBinding binding, boolean scan, OpenMetadataPipelineRun run, Date now) {
        MetadataRunStatus currentStatus = scan ? binding.getScanStatus() : binding.getProfileStatus();
        Date currentLastRunTime = scan ? binding.getScanLastRunTime() : binding.getProfileLastRunTime();
        if (run != null && isOlderThanLocalRun(run, currentStatus, currentLastRunTime)) {
            // A user-triggered run is reserved locally before OpenMetadata registers it.
            // Do not replace a newer local run state with the previous run returned by OM.
            // If OM never registered a queued scan, reopen that reservation after the same
            // grace period used for a missing run so the version-driven trigger can retry it.
            if (scan
                    && currentStatus == MetadataRunStatus.QUEUED
                    && currentLastRunTime != null
                    && now.getTime() - currentLastRunTime.getTime()
                            >= properties.getTriggerGraceSeconds() * 1000L
                    && binding.getSyncedConfigVersion() != null
                    && binding.getSyncedConfigVersion() > 0
                    && binding.getMetadataTriggeredVersion() != null) {
                long syncedVersion = binding.getSyncedConfigVersion();
                int retryCount = (binding.getRetryCount() == null ? 0 : binding.getRetryCount()) + 1;
                binding.setRetryCount(retryCount);
                if (retryCount > maxScanTriggerRetries()) {
                    // The ingestion pipeline never registered the run. Stop re-triggering it and
                    // leave a visible failure instead of cycling QUEUED/NEVER forever.
                    binding.setScanStatus(MetadataRunStatus.FAILED);
                    binding.setScanLastError(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name());
                    return;
                }
                if (binding.getMetadataTriggeredVersion() >= syncedVersion) {
                    binding.setMetadataTriggeredVersion(syncedVersion - 1L);
                }
                binding.setScanStatus(MetadataRunStatus.NEVER);
            }
            return;
        }
        if (run == null
                && MetadataPipelineOperationService.isRunning(currentStatus)
                && currentLastRunTime != null
                && now.getTime() - currentLastRunTime.getTime() < properties.getTriggerGraceSeconds() * 1000L) {
            return;
        }
        if (run == null
                && (currentStatus == MetadataRunStatus.FAILED || currentStatus == MetadataRunStatus.SUCCESS)) {
            // Local terminal outcomes (for example exploration upsert timeout) must not be
            // wiped to NEVER just because OpenMetadata never recorded a pipeline run.
            return;
        }
        if (run == null && currentStatus == MetadataRunStatus.NEVER && hasLocalFailureEvidence(binding, scan)) {
            // Recover rows that an older synchronizer wiped to NEVER while leaving lastError set.
            if (scan) {
                binding.setScanStatus(MetadataRunStatus.FAILED);
            } else {
                binding.setProfileStatus(MetadataRunStatus.FAILED);
            }
            return;
        }
        if (scan
                && run == null
                && currentStatus == MetadataRunStatus.QUEUED
                && binding.getSyncedConfigVersion() != null
                && binding.getMetadataTriggeredVersion() != null
                && binding.getMetadataTriggeredVersion() >= binding.getSyncedConfigVersion()) {
            // The durable reservation survived but OM never registered a run (for example,
            // the process crashed before trigger). Re-open exactly this synced version.
            binding.setMetadataTriggeredVersion(Math.max(0L, binding.getSyncedConfigVersion() - 1L));
        }
        MetadataRunStatus status;
        if (run == null) {
            if (!scan && MetadataPipelineOperationService.isRunning(currentStatus)) {
                // Exploration was reserved locally but never appeared in OM after the grace window.
                status = MetadataRunStatus.FAILED;
            } else {
                // Metadata scan keeps NEVER so the reconciler can reopen and retry.
                status = MetadataRunStatus.NEVER;
            }
        } else {
            status = OpenMetadataRunStatusMapper.fromPipelineState(run.pipelineState());
        }
        Date runTime = run == null
                ? null
                : MetadataPipelineOperationService.fromOmTimestamp(
                        run.startDate() == null ? run.timestamp() : run.startDate());
        Date successTime = run != null && status == MetadataRunStatus.SUCCESS
                ? MetadataPipelineOperationService.fromOmTimestamp(
                        run.endDate() != null
                                ? run.endDate()
                                : run.startDate() == null ? run.timestamp() : run.startDate())
                : null;
        if (scan) {
            binding.setScanStatus(status);
            if (runTime != null) {
                binding.setScanLastRunTime(runTime);
            }
            if (successTime != null) {
                binding.setScanLastSuccessTime(successTime);
                binding.setScanLastError(null);
            } else if (status == MetadataRunStatus.FAILED) {
                binding.setScanLastError(MetadataErrorCode.PIPELINE_EXECUTION_ERROR.name());
            }
        } else {
            binding.setProfileStatus(status);
            if (runTime != null) {
                binding.setProfileLastRunTime(runTime);
            }
            if (successTime != null) {
                binding.setProfileLastSuccessTime(successTime);
                binding.setProfileLastError(null);
            } else if (status == MetadataRunStatus.FAILED) {
                if (run == null) {
                    binding.setProfileLastError(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR.name());
                } else {
                    binding.setProfileLastError(MetadataErrorCode.PIPELINE_EXECUTION_ERROR.name());
                }
            }
        }
    }

    private static boolean isOlderThanLocalRun(
            OpenMetadataPipelineRun run, MetadataRunStatus currentStatus, Date currentLastRunTime) {
        if (currentStatus == null || currentStatus == MetadataRunStatus.NEVER || currentLastRunTime == null) {
            return false;
        }
        // OpenMetadata writes startDate and timestamp from the same wall clock: the ingestion
        // workflow sets both to its start time (workflow_status_mixin._new_pipeline_status) and the
        // server does the same for a queued run (IngestionPipelineRepository.recordQueuedPipelineStatus).
        // Prefer startDate so an older OM run cannot mask a newer local reservation; a run that only
        // carries the execution timestamp is still placed in time instead of being dropped.
        Long timestamp = run.startDate() == null ? run.timestamp() : run.startDate();
        Date runTime = MetadataPipelineOperationService.fromOmTimestamp(timestamp);
        // OM timestamps are commonly second-precision; allow a small clock/precision skew.
        return runTime != null && runTime.getTime() + 5_000L < currentLastRunTime.getTime();
    }

    private static boolean hasLocalFailureEvidence(MetadataSourceBinding binding, boolean scan) {
        String error = scan ? binding.getScanLastError() : binding.getProfileLastError();
        Date lastRunTime = scan ? binding.getScanLastRunTime() : binding.getProfileLastRunTime();
        return error != null && !error.isBlank() && lastRunTime != null;
    }
}
