package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataEntity;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Desired-state reconciler. No local transaction spans an OpenMetadata request.
 * Claiming is database-backed so separate SeaTunnel Web nodes cannot reconcile a
 * binding concurrently.
 */
@Slf4j
@Service
public class MetadataSourceReconciler {

    private final MetadataBindingDao metadataBindingDao;
    private final DataSourceDao dataSourceDao;
    private final MetadataConnectorRegistry connectorRegistry;
    private final OpenMetadataClient openMetadataClient;
    private final MetadataReconcileProperties properties;

    public MetadataSourceReconciler(
            MetadataBindingDao metadataBindingDao,
            DataSourceDao dataSourceDao,
            MetadataConnectorRegistry connectorRegistry,
            OpenMetadataClient openMetadataClient,
            MetadataReconcileProperties properties) {
        this.metadataBindingDao = metadataBindingDao;
        this.dataSourceDao = dataSourceDao;
        this.connectorRegistry = connectorRegistry;
        this.openMetadataClient = openMetadataClient;
        this.properties = properties;
    }

    public void reconcilePendingBindings() {
        Date now = new Date();
        Date staleClaimBefore = Date.from(Instant.ofEpochMilli(now.getTime()).minusSeconds(properties.getLeaseSeconds()));
        List<MetadataSourceBinding> candidates =
                metadataBindingDao.queryReconcileCandidates(now, staleClaimBefore, properties.getBatchSize());
        for (MetadataSourceBinding candidate : candidates) {
            reconcileCandidate(candidate, now);
        }
    }

    void reconcileCandidate(MetadataSourceBinding candidate, Date now) {
        if (candidate == null || candidate.getId() == null || candidate.getVersion() == null) {
            return;
        }
        Date staleClaimBefore = Date.from(Instant.ofEpochMilli(now.getTime()).minusSeconds(properties.getLeaseSeconds()));
        if (!metadataBindingDao.tryClaim(candidate.getId(), candidate.getVersion(), now, staleClaimBefore)) {
            return;
        }
        long claimedVersion = candidate.getVersion() + 1L;
        candidate.setVersion(claimedVersion);
        candidate.setSyncStatus(MetadataSyncStatus.SYNCING);
        try {
            if (candidate.getDesiredState() == MetadataDesiredState.DELETED) {
                reconcileDeletion(candidate, claimedVersion);
            } else {
                reconcileActive(candidate, claimedVersion);
            }
        } catch (MetadataIntegrationException e) {
            saveFailure(candidate, claimedVersion, e.getErrorCode());
        } catch (Exception e) {
            log.warn("Metadata reconcile failed: dataSourceId={}, code={}", candidate.getDataSourceId(),
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR);
            saveFailure(candidate, claimedVersion, MetadataErrorCode.OM_SERVICE_SYNC_ERROR);
        }
    }

    private void reconcileActive(MetadataSourceBinding claimed, long claimedVersion) {
        DataSource dataSource = dataSourceDao.queryById(claimed.getDataSourceId());
        if (dataSource == null) {
            // A legacy physical deletion can still be cleaned up from the binding's OM identities.
            claimed.setDesiredState(MetadataDesiredState.DELETED);
            claimed.setSyncStatus(MetadataSyncStatus.DELETING);
            complete(claimed, claimedVersion);
            return;
        }
        openMetadataClient.assertFixedVersion();
        MetadataConnectorAdapter adapter = connectorRegistry.require(dataSource.getDbType());
        String serviceName = MetadataStableName.serviceName(dataSource.getId());

        // PUT is the documented 1.12.10 upsert, so this also converges changed source configuration.
        OpenMetadataEntity service = openMetadataClient.upsertDatabaseService(
                adapter.databaseServiceRequest(dataSource, serviceName));
        OpenMetadataEntity metadataPipeline = openMetadataClient.upsertIngestionPipeline(
                adapter.metadataPipelineRequest(
                        dataSource,
                        MetadataStableName.metadataPipelineName(dataSource.getId()),
                        service.id(), service.fullyQualifiedName()));
        OpenMetadataEntity profilerPipeline = openMetadataClient.upsertIngestionPipeline(
                adapter.profilerPipelineRequest(
                        MetadataStableName.profilerPipelineName(dataSource.getId()),
                        service.id(), service.fullyQualifiedName()));
        // The 1.12.10 deploy endpoints deliberately have no request body.
        openMetadataClient.deployIngestionPipeline(metadataPipeline.id());
        openMetadataClient.enableIngestionPipeline(metadataPipeline.id());
        openMetadataClient.deployIngestionPipeline(profilerPipeline.id());
        openMetadataClient.enableIngestionPipeline(profilerPipeline.id());

        MetadataSourceBinding latest = metadataBindingDao.queryById(claimed.getId());
        if (!owned(latest, claimedVersion)) {
            return;
        }
        applyOmIdentities(latest, service, metadataPipeline, profilerPipeline);
        if (!sameVersion(latest.getConfigVersion(), claimed.getConfigVersion())) {
            latest.setSyncStatus(MetadataSyncStatus.PENDING);
        } else {
            latest.setSyncedConfigVersion(latest.getConfigVersion());
            latest.setSyncStatus(MetadataSyncStatus.READY);
        }
        latest.setRetryCount(0);
        latest.setNextRetryTime(null);
        latest.setLastSyncErrorCode(null);
        latest.setLastSyncError(null);
        complete(latest, claimedVersion);
    }

    private void reconcileDeletion(MetadataSourceBinding claimed, long claimedVersion) {
        openMetadataClient.assertFixedVersion();
        String metadataFqn = defaultIfBlank(
                claimed.getOmMetadataPipelineFqn(),
                MetadataStableName.metadataPipelineFqn(claimed.getDataSourceId()));
        String profilerFqn = defaultIfBlank(
                claimed.getOmProfilerPipelineFqn(),
                MetadataStableName.profilerPipelineFqn(claimed.getDataSourceId()));
        deletePipeline(claimed.getOmMetadataPipelineId(), metadataFqn);
        deletePipeline(claimed.getOmProfilerPipelineId(), profilerFqn);
        String serviceId = claimed.getOmServiceId();
        if (serviceId != null && !serviceId.isBlank()) {
            openMetadataClient.deleteDatabaseServiceRecursively(serviceId);
        } else {
            openMetadataClient.findDatabaseService(defaultIfBlank(
                            claimed.getOmServiceFqn(), MetadataStableName.serviceFqn(claimed.getDataSourceId())))
                    .ifPresent(service -> openMetadataClient.deleteDatabaseServiceRecursively(service.id()));
        }
        if (metadataBindingDao.deleteClaimed(claimed.getId(), claimedVersion)) {
            // The local source stayed available until external cleanup completed.
            dataSourceDao.deleteById(claimed.getDataSourceId());
        }
    }

    private void deletePipeline(String id, String fqn) {
        String resolvedId = id;
        if (resolvedId == null || resolvedId.isBlank()) {
            resolvedId = openMetadataClient.findIngestionPipeline(fqn)
                    .map(OpenMetadataEntity::id)
                    .orElse(null);
        }
        if (resolvedId == null || resolvedId.isBlank()) {
            return;
        }
        if (isRunning(fqn)) {
            openMetadataClient.killIngestionPipeline(resolvedId);
        }
        openMetadataClient.deleteIngestionPipeline(resolvedId);
    }

    private boolean isRunning(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return false;
        }
        return openMetadataClient.listIngestionPipelineRuns(fqn, 1).stream()
                .map(run -> OpenMetadataRunStatusMapper.fromPipelineState(run.pipelineState()))
                .anyMatch(status -> status == org.apache.seatunnel.web.common.enums.MetadataRunStatus.QUEUED
                        || status == org.apache.seatunnel.web.common.enums.MetadataRunStatus.RUNNING);
    }

    private void saveFailure(MetadataSourceBinding claimed, long claimedVersion, MetadataErrorCode errorCode) {
        MetadataSourceBinding latest = metadataBindingDao.queryById(claimed.getId());
        if (!owned(latest, claimedVersion)) {
            return;
        }
        int retryCount = (latest.getRetryCount() == null ? 0 : latest.getRetryCount()) + 1;
        latest.setRetryCount(retryCount);
        latest.setSyncStatus(MetadataSyncStatus.ERROR);
        latest.setLastSyncErrorCode(errorCode.name());
        latest.setLastSyncError("OpenMetadata reconciliation failed; see sanitized server logs for correlation.");
        latest.setNextRetryTime(retryCount > properties.getMaxRetryCount()
                ? null
                : Date.from(Instant.now().plusSeconds(retryDelaySeconds(retryCount))));
        complete(latest, claimedVersion);
    }

    private void complete(MetadataSourceBinding binding, long claimedVersion) {
        binding.setVersion(claimedVersion + 1L);
        binding.initUpdate();
        metadataBindingDao.updateClaimed(binding, claimedVersion);
    }

    private boolean owned(MetadataSourceBinding binding, long claimedVersion) {
        return binding != null && binding.getSyncStatus() == MetadataSyncStatus.SYNCING
                && Long.valueOf(claimedVersion).equals(binding.getVersion());
    }

    private static boolean sameVersion(Long left, Long right) {
        return left != null && left.equals(right);
    }

    private static void applyOmIdentities(
            MetadataSourceBinding binding,
            OpenMetadataEntity service,
            OpenMetadataEntity metadataPipeline,
            OpenMetadataEntity profilerPipeline) {
        binding.setOmServiceId(service.id());
        binding.setOmServiceFqn(service.fullyQualifiedName());
        binding.setOmMetadataPipelineId(metadataPipeline.id());
        binding.setOmMetadataPipelineFqn(metadataPipeline.fullyQualifiedName());
        binding.setOmProfilerPipelineId(profilerPipeline.id());
        binding.setOmProfilerPipelineFqn(profilerPipeline.fullyQualifiedName());
    }

    private static long retryDelaySeconds(int retryCount) {
        return switch (retryCount) {
            case 1 -> 60;
            case 2 -> 5 * 60;
            case 3 -> 15 * 60;
            default -> 30 * 60;
        };
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

}
