package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineRun;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
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

    /** Optional to keep existing unit-test constructors and lightweight deployments compatible. */
    @Autowired(required = false)
    private MetadataInventoryCache metadataInventoryCache;

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
            MetadataSourceBinding latest = metadataBindingDao.queryById(candidate.getId());
            if (!owned(latest, candidate.getVersion())) {
                return;
            }
            applyRun(latest, true, latestRun(scanRuns), now);
            applyRun(latest, false, latestRun(profileRuns), now);
            latest.setLastStatusRefreshTime(now);
            latest.setStatusRefreshError(null);
            long version = latest.getVersion();
            latest.setVersion(version + 1L);
            latest.initUpdate();
            if (metadataBindingDao.updateIfVersion(latest, version)) {
                if (metadataInventoryCache != null) {
                    metadataInventoryCache.invalidateDataSource(candidate.getDataSourceId());
                }
                operationService.triggerPendingMetadataScan(latest);
            }
        } catch (Exception e) {
            markUnknown(candidate, now);
        }
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
        if (MetadataPipelineOperationService.isRunning(latest.getProfileStatus())) {
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
                || MetadataPipelineOperationService.isRunning(binding.getProfileStatus())) {
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

    private static boolean owned(MetadataSourceBinding binding, Long expectedVersion) {
        return binding != null && expectedVersion.equals(binding.getVersion());
    }

    private static OpenMetadataPipelineRun latestRun(List<OpenMetadataPipelineRun> runs) {
        return runs.stream().max(Comparator.comparing(run -> timestamp(run))).orElse(null);
    }

    private static long timestamp(OpenMetadataPipelineRun run) {
        if (run.timestamp() != null) {
            return run.timestamp();
        }
        return run.startDate() == null ? 0L : run.startDate();
    }

    private void applyRun(MetadataSourceBinding binding, boolean scan, OpenMetadataPipelineRun run, Date now) {
        MetadataRunStatus currentStatus = scan ? binding.getScanStatus() : binding.getProfileStatus();
        Date currentLastRunTime = scan ? binding.getScanLastRunTime() : binding.getProfileLastRunTime();
        if (run != null && isOlderThanLocalRun(run, currentStatus, currentLastRunTime)) {
            // A user-triggered run is reserved locally before OpenMetadata registers it.
            // Do not replace a newer local run state with the previous run returned by OM.
            return;
        }
        if (run == null
                && MetadataPipelineOperationService.isRunning(currentStatus)
                && currentLastRunTime != null
                && now.getTime() - currentLastRunTime.getTime() < properties.getTriggerGraceSeconds() * 1000L) {
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
        MetadataRunStatus status = run == null
                ? MetadataRunStatus.NEVER
                : OpenMetadataRunStatusMapper.fromPipelineState(run.pipelineState());
        Date runTime = run == null
                ? null
                : MetadataPipelineOperationService.fromOmTimestamp(
                        run.timestamp() == null ? run.startDate() : run.timestamp());
        Date successTime = status == MetadataRunStatus.SUCCESS
                ? MetadataPipelineOperationService.fromOmTimestamp(
                        run.endDate() == null ? run.timestamp() : run.endDate())
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
                binding.setProfileLastError(MetadataErrorCode.PIPELINE_EXECUTION_ERROR.name());
            }
        }
    }

    private static boolean isOlderThanLocalRun(
            OpenMetadataPipelineRun run, MetadataRunStatus currentStatus, Date currentLastRunTime) {
        if (currentStatus == null || currentStatus == MetadataRunStatus.NEVER || currentLastRunTime == null) {
            return false;
        }
        Long timestamp = run.timestamp() == null ? run.startDate() : run.timestamp();
        Date runTime = MetadataPipelineOperationService.fromOmTimestamp(timestamp);
        // OM timestamps are commonly second-precision; allow a small clock/precision skew.
        return runTime != null && runTime.getTime() + 5_000L < currentLastRunTime.getTime();
    }
}
