package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataSyncOptions;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataEntity;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabaseSchema;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineRun;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceMetadataStatusVO;
import org.apache.seatunnel.web.spi.bean.vo.MetadataPipelineRunVO;
import org.apache.seatunnel.web.spi.bean.vo.MetadataRunStateVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * User and scheduler operations on the existing DataSource binding. Every external
 * request goes through OpenMetadata Server 2.0.4; no Airflow client exists here.
 */
@Slf4j
@Service
public class MetadataPipelineOperationService {

    private static final int MAX_OM_PAGE_SIZE = 1000;
    private static final int MAX_OM_PAGES = 10_000;
    private static final long RESERVATION_TIME_TOLERANCE_MILLIS = 5_000L;
    private static final int MAX_PIPELINE_TRIGGER_ATTEMPTS = 32;
    private static final long INITIAL_PIPELINE_TRIGGER_RETRY_DELAY_MILLIS = 2_000L;
    private static final long MAX_PIPELINE_TRIGGER_RETRY_DELAY_MILLIS = 60_000L;
    private static final long MAX_PIPELINE_TRIGGER_RETRY_BUDGET_MILLIS = 1_200_000L;
    private static final long PIPELINE_TRIGGER_DEADLINE_MARGIN_MILLIS = 20_000L;

    private record ExplorationRunBaseline(String profilerRunId, String sampleRunId) {}

    private record PipelineTriggerBudget(
            long retryStartDeadlineNanos,
            long preparationDeadlineNanos,
            long requestTimeoutNanos) {}

    private static final class PendingPipelineTrigger {
        private final String pipelineType;
        private final String pipelineId;
        private final String pipelineFqn;
        private final String baselineRunId;
        private MetadataIntegrationException lastFailure;

        private PendingPipelineTrigger(
                String pipelineType,
                String pipelineId,
                String pipelineFqn,
                String baselineRunId,
                MetadataIntegrationException lastFailure) {
            this.pipelineType = pipelineType;
            this.pipelineId = pipelineId;
            this.pipelineFqn = pipelineFqn;
            this.baselineRunId = baselineRunId;
            this.lastFailure = lastFailure;
        }
    }

    /** Local reservation returned before the potentially slow OpenMetadata work starts. */
    public record ExplorationReservation(
            Long bindingId,
            Long dataSourceId,
            String databaseFqn,
            String schemaFqn,
            long reservedVersion,
            Date reservedAt,
            String reservationToken) {

        /** Keeps callers that already pass a schema source-compatible. */
        public ExplorationReservation(
                Long bindingId,
                Long dataSourceId,
                String databaseFqn,
                String schemaFqn,
                long reservedVersion,
                Date reservedAt) {
            this(bindingId, dataSourceId, databaseFqn, schemaFqn, reservedVersion, reservedAt, null);
        }

        /** Keeps callers that do not select a schema source-compatible. */
        public ExplorationReservation(
                Long bindingId,
                Long dataSourceId,
                String databaseFqn,
                long reservedVersion,
                Date reservedAt) {
            this(bindingId, dataSourceId, databaseFqn, null, reservedVersion, reservedAt, null);
        }
    }

    private final OpenMetadataConfigResolver configResolver;
    private final MetadataBindingDao metadataBindingDao;
    private final DataSourceDao dataSourceDao;
    private final MetadataConnectorRegistry connectorRegistry;
    private final OpenMetadataClient openMetadataClient;
    private final MetadataBindingCommandService metadataBindingCommandService;
    private volatile OmReadCache omReadCache = OmReadCache.disabled();

    private MetadataStatusProperties metadataStatusProperties = new MetadataStatusProperties();
    private MetadataInventoryCache metadataInventoryCache;

    @Autowired
    public MetadataPipelineOperationService(
            OpenMetadataConfigResolver configResolver,
            MetadataBindingDao metadataBindingDao,
            DataSourceDao dataSourceDao,
            MetadataConnectorRegistry connectorRegistry,
            OpenMetadataClient openMetadataClient,
            MetadataBindingCommandService metadataBindingCommandService) {
        this.configResolver = configResolver;
        this.metadataBindingDao = metadataBindingDao;
        this.dataSourceDao = dataSourceDao;
        this.connectorRegistry = connectorRegistry;
        this.openMetadataClient = openMetadataClient;
        this.metadataBindingCommandService = metadataBindingCommandService;
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

    @Autowired(required = false)
    void setMetadataStatusProperties(MetadataStatusProperties metadataStatusProperties) {
        if (metadataStatusProperties != null) {
            this.metadataStatusProperties = metadataStatusProperties;
        }
    }

    /** Backward-compatible constructor for unit tests that still pass properties. */
    public MetadataPipelineOperationService(
            OpenMetadataProperties openMetadataProperties,
            MetadataBindingDao metadataBindingDao,
            DataSourceDao dataSourceDao,
            MetadataConnectorRegistry connectorRegistry,
            OpenMetadataClient openMetadataClient,
            MetadataBindingCommandService metadataBindingCommandService) {
        this(OpenMetadataConfigResolver.fixed(openMetadataProperties),
                metadataBindingDao,
                dataSourceDao,
                connectorRegistry,
                openMetadataClient,
                metadataBindingCommandService);
    }

    /**
     * Requests a desired-state reconciliation without changing the local data
     * source or any SeaTunnel job reference. This is useful after a connector
     * patch or an infrastructure-only metadata endpoint change.
     */
    public boolean reconcileMetadata(Long dataSourceId) {
        requireActiveDataSource(dataSourceId);
        metadataBindingCommandService.markConfigurationChanged(dataSourceId);
        return true;
    }

    /**
     * Records the operator decision for OpenMetadata sample-data collection. Enabling it
     * lets the ingestion pipeline read real payloads (Kafka topic messages, SFTP file
     * rows) into OpenMetadata; it stays off unless this is called explicitly.
     */
    public boolean updateSampleDataCollection(Long dataSourceId, boolean enabled) {
        requireEnabled();
        DataSource dataSource = requireActiveDataSource(dataSourceId);
        MetadataConnectorAdapter adapter = connectorRegistry.find(dataSource.getDbType())
                .orElseThrow(() -> invalid("metadata connector is not supported for this data source type"));
        if (!adapter.supportsSampleData()) {
            throw invalid("sample data collection is not supported for this data source type");
        }
        metadataBindingCommandService.markSampleDataChanged(dataSourceId, enabled);
        return true;
    }

    /**
     * Records the operator's object-storage manifest. It is what turns a plain container
     * into a structured one with a data model, and it stays OpenMetadata-only.
     */
    public boolean updateStorageManifest(Long dataSourceId, String manifest) {
        requireEnabled();
        DataSource dataSource = requireActiveDataSource(dataSourceId);
        MetadataConnectorAdapter adapter = connectorRegistry.find(dataSource.getDbType())
                .orElseThrow(() -> invalid("metadata connector is not supported for this data source type"));
        if (!adapter.supportsStorageManifest()) {
            throw invalid("object storage manifests are not supported for this data source type");
        }
        metadataBindingCommandService.markStorageManifestChanged(
                dataSourceId, normalizeStorageManifest(manifest));
        return true;
    }

    /**
     * Accepts either an empty value (clear the manifest) or the JSON a bucket-level
     * {@code openmetadata.json} would hold: an object with an {@code entries} array.
     */
    static String normalizeStorageManifest(String manifest) {
        if (manifest == null || manifest.isBlank()) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = new ObjectMapper().readTree(manifest);
        } catch (JsonProcessingException error) {
            throw invalid("storage manifest must be valid JSON");
        }
        if (parsed == null || !parsed.isObject() || !parsed.path("entries").isArray()) {
            throw invalid("storage manifest must be an object with an entries array");
        }
        return parsed.toString();
    }

    public boolean triggerScan(Long dataSourceId) {
        requireActiveDataSource(dataSourceId);
        MetadataSourceBinding binding = requireBinding(dataSourceId);
        if (binding.getDesiredState() != MetadataDesiredState.ACTIVE) {
            throw invalid("metadata synchronization is not ready");
        }
        // Operator "重新扫描" on a failed control-plane sync should reopen the
        // binding instead of requiring a separate retry endpoint.
        if (binding.getSyncStatus() == MetadataSyncStatus.ERROR) {
            if (MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name().equals(binding.getLastSyncErrorCode())) {
                throw invalid("metadata connector is not supported for this data source type");
            }
            return retryMetadataSync(dataSourceId);
        }
        if (binding.getSyncStatus() != MetadataSyncStatus.READY) {
            throw invalid("metadata synchronization is not ready");
        }
        triggerMetadata(binding, true);
        return true;
    }

    public boolean triggerExploration(Long dataSourceId, String databaseFqn) {
        return triggerExploration(dataSourceId, databaseFqn, null);
    }

    public boolean triggerExploration(Long dataSourceId, String databaseFqn, String schemaFqn) {
        if (databaseFqn == null || databaseFqn.isBlank()) {
            throw invalid("databaseFqn");
        }
        MetadataSourceBinding binding = requireReadyBinding(dataSourceId);
        DataSource dataSource = requireProfilerCapableDataSource(dataSourceId);
        String serviceFqn = requireServiceFqn(binding, dataSourceId);
        OpenMetadataDatabase database = openMetadataClient.findDatabase(databaseFqn)
                .orElseThrow(() -> invalid("databaseFqn does not exist"));
        if (!serviceFqn.equals(database.serviceFullyQualifiedName())) {
            throw invalid("databaseFqn does not belong to this data source");
        }
        requireOwnedSchema(serviceFqn, databaseFqn, schemaFqn);
        openMetadataClient.assertFixedVersion();
        ensureNoRunningPipeline(binding);
        long initialVersion = requireVersion(binding);
        Date now = new Date();
        String token = UUID.randomUUID().toString();
        if (!metadataBindingDao.reserveRun(binding.getId(), initialVersion, false, null, now, token)) {
            throw invalid("a scan or exploration is already running");
        }
        ExplorationReservation reservation = new ExplorationReservation(
                binding.getId(), dataSourceId, databaseFqn, schemaFqn,
                initialVersion + 1L, now, token);
        try {
            MetadataConnectorAdapter adapter = connectorRegistry.require(dataSource.getDbType());
            if (!triggerExplorationPipelines(
                    adapter, dataSourceId, requireProfilerServiceId(binding, dataSourceId),
                    serviceFqn, databaseFqn, schemaFqn, reservation)) {
                return false;
            }
            completeExplorationReservation(reservation);
            return true;
        } catch (MetadataIntegrationException e) {
            failExplorationReservation(reservation, e.getErrorCode());
            throw operationFailure("data-source exploration could not be triggered");
        } catch (Exception e) {
            failExplorationReservation(reservation, MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR);
            throw operationFailure("data-source exploration could not be triggered");
        }
    }

    /**
     * Reserves an exploration in the local control plane without waiting for
     * OpenMetadata to deploy and trigger the profiler pipeline.
     */
    public ExplorationReservation reserveExploration(Long dataSourceId, String databaseFqn) {
        return reserveExploration(dataSourceId, databaseFqn, null);
    }

    public ExplorationReservation reserveExploration(
            Long dataSourceId, String databaseFqn, String schemaFqn) {
        if (databaseFqn == null || databaseFqn.isBlank()) {
            throw invalid("databaseFqn");
        }
        requireEnabled();
        MetadataSourceBinding binding = requireReadyBinding(dataSourceId);
        requireProfilerCapableDataSource(dataSourceId);
        long initialVersion = requireVersion(binding);
        Date now = new Date();
        String token = UUID.randomUUID().toString();
        if (!metadataBindingDao.reserveRun(binding.getId(), initialVersion, false, null, now, token)) {
            throw invalid("a scan or exploration is already running");
        }
        return new ExplorationReservation(
                binding.getId(), dataSourceId, databaseFqn, schemaFqn,
                initialVersion + 1L, now, token);
    }

    /**
     * Performs the external pipeline operations off the request thread. The
     * controller invokes this method through the Spring proxy, so the local
     * QUEUED state is visible to the caller immediately.
     */
    @Async("metadataExplorationExecutor")
    public void executeExploration(ExplorationReservation reservation) {
        if (reservation == null || reservation.reservationToken() == null
                || reservation.reservationToken().isBlank()) {
            log.warn("Ignoring metadata exploration without a reservation token");
            return;
        }
        try {
            requireCurrentExplorationReservation(reservation);
            MetadataSourceBinding binding = metadataBindingDao.queryById(reservation.bindingId());
            if (binding == null) {
                throw invalid("metadata binding is not initialized");
            }
            DataSource dataSource = requireActiveDataSource(reservation.dataSourceId());
            String serviceFqn = requireServiceFqn(binding, reservation.dataSourceId());
            OpenMetadataDatabase database = openMetadataClient.findDatabase(reservation.databaseFqn())
                    .orElseThrow(() -> invalid("databaseFqn does not exist"));
            if (!serviceFqn.equals(database.serviceFullyQualifiedName())) {
                throw invalid("databaseFqn does not belong to this data source");
            }
            requireOwnedSchema(serviceFqn, reservation.databaseFqn(), reservation.schemaFqn());
            openMetadataClient.assertFixedVersion();
            ensureNoRunningPipelineForReservedExploration(binding);

            MetadataConnectorAdapter adapter = connectorRegistry.require(dataSource.getDbType());
            if (!triggerExplorationPipelines(
                    adapter, reservation.dataSourceId(),
                    requireProfilerServiceId(binding, reservation.dataSourceId()),
                    serviceFqn, reservation.databaseFqn(), reservation.schemaFqn(), reservation)) {
                return;
            }
            completeExplorationReservation(reservation);
            log.info("Data-source exploration triggered: dataSourceId={}, databaseFqn={}, schemaFqn={}",
                    reservation.dataSourceId(), reservation.databaseFqn(), reservation.schemaFqn());
        } catch (MetadataIntegrationException e) {
            MetadataErrorCode errorCode = e.getErrorCode() == null
                    ? MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR
                    : e.getErrorCode();
            failExplorationReservation(reservation, errorCode);
            log.warn("Data-source exploration failed: dataSourceId={}, errorCode={}",
                    reservation.dataSourceId(), errorCode);
        } catch (Exception e) {
            failExplorationReservation(reservation, MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR);
            log.warn("Data-source exploration failed: dataSourceId={}, type={}",
                    reservation.dataSourceId(), e.getClass().getSimpleName());
        }
    }

    public boolean retryMetadataSync(Long dataSourceId) {
        MetadataSourceBinding binding = requireBinding(dataSourceId);
        if (binding.getSyncStatus() != MetadataSyncStatus.ERROR) {
            throw invalid("metadata sync is not in an error state");
        }
        long version = requireVersion(binding);
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        binding.setRetryCount(0);
        binding.setNextRetryTime(new Date());
        binding.setLastSyncErrorCode(null);
        binding.setLastSyncError(null);
        binding.setVersion(version + 1L);
        binding.initUpdate();
        if (!metadataBindingDao.updateIfVersion(binding, version)) {
            throw invalid("metadata sync state changed; refresh and retry");
        }
        return true;
    }

    public DataSourceMetadataStatusVO getCachedStatus(Long dataSourceId) {
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        DataSourceMetadataStatusVO status = new DataSourceMetadataStatusVO();
        status.setSampleDataSupported(supportsSampleData(dataSourceId));
        status.setStorageManifestSupported(supportsStorageManifest(dataSourceId));
        if (binding == null) {
            status.setSyncStatus("NOT_INITIALIZED");
            status.setSampleDataEnabled(false);
            status.setScan(runState(MetadataRunStatus.NEVER, null, null, null));
            status.setExploration(runState(MetadataRunStatus.NEVER, null, null, null));
            return status;
        }
        status.setSyncStatus(MetadataSyncStatusView.project(binding));
        status.setSampleDataEnabled(Boolean.TRUE.equals(binding.getSampleDataEnabled()));
        status.setStorageManifest(binding.getStorageManifestConfig());
        status.setScan(runState(
                effectiveRunStatus(binding.getScanStatus(), binding.getScanLastError(), binding.getScanLastRunTime()),
                binding.getScanLastRunTime(),
                binding.getScanLastSuccessTime(),
                binding.getScanLastError()));
        status.setExploration(runState(
                effectiveRunStatus(
                        binding.getProfileStatus(), binding.getProfileLastError(), binding.getProfileLastRunTime()),
                binding.getProfileLastRunTime(),
                binding.getProfileLastSuccessTime(),
                binding.getProfileLastError()));
        return status;
    }

    private static MetadataRunStatus effectiveRunStatus(
            MetadataRunStatus status, String lastError, Date lastRunTime) {
        if (status == MetadataRunStatus.NEVER
                && lastError != null && !lastError.isBlank()
                && lastRunTime != null) {
            return MetadataRunStatus.FAILED;
        }
        return status;
    }

    public List<MetadataPipelineRunVO> listRuns(Long dataSourceId, String type, int limit) {
        requireEnabled();
        MetadataSourceBinding binding = requireReadyBinding(dataSourceId);
        boolean exploration = "EXPLORATION".equalsIgnoreCase(type);
        if (!exploration && !"SCAN".equalsIgnoreCase(type)) {
            throw invalid("type must be SCAN or EXPLORATION");
        }
        String pipelineFqn = exploration ? binding.getOmProfilerPipelineFqn() : binding.getOmMetadataPipelineFqn();
        if (pipelineFqn == null || pipelineFqn.isBlank()) {
            // Non-database connectors never create a profiler pipeline. Return an empty
            // exploration history instead of failing callers that still ask for it.
            if (exploration) {
                return List.of();
            }
            throw invalid("pipeline has not been synchronized");
        }
        openMetadataClient.assertFixedVersion();
        int safeLimit = Math.max(1, limit);
        List<MetadataPipelineRunVO> result = new ArrayList<>();
        result.addAll(toRunViews(pipelineFqn, safeLimit, exploration ? "PROFILER" : "METADATA"));
        if (exploration) {
            String sampleFqn = MetadataStableName.autoClassificationPipelineFqn(dataSourceId);
            result.addAll(toRunViews(sampleFqn, safeLimit, "AUTO_CLASSIFICATION"));
        }
        MetadataPipelineRunVO localFailure = localFailureRun(binding, exploration);
        if (localFailure != null && !hasMatchingOmFailure(result, localFailure)) {
            result.add(localFailure);
        }
        result.sort(Comparator.comparing(
                MetadataPipelineRunVO::getStartTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result.size() > safeLimit
                ? new ArrayList<>(result.subList(0, safeLimit))
                : result;
    }

    private List<MetadataPipelineRunVO> toRunViews(String fqn, int limit, String pipelineType) {
        List<MetadataPipelineRunVO> result = new ArrayList<>();
        for (OpenMetadataPipelineRun run : openMetadataClient.listIngestionPipelineRuns(fqn, limit)) {
            MetadataPipelineRunVO item = new MetadataPipelineRunVO();
            item.setRunId(run.runId());
            item.setPipelineType(pipelineType);
            item.setStatus(OpenMetadataRunStatusMapper.fromPipelineState(run.pipelineState()));
            item.setStartTime(fromOmTimestamp(firstNonNull(run.startDate(), run.timestamp())));
            item.setEndTime(fromOmTimestamp(run.endDate()));
            item.setWarningsCount(run.warningsCount());
            result.add(item);
        }
        return result;
    }

    /**
     * Surfaces control-plane failures that never produced an OpenMetadata pipeline run
     * (for example exploration upsert timeout) so the UI run history is not empty.
     */
    private static MetadataPipelineRunVO localFailureRun(MetadataSourceBinding binding, boolean exploration) {
        MetadataRunStatus status = exploration ? binding.getProfileStatus() : binding.getScanStatus();
        String error = exploration ? binding.getProfileLastError() : binding.getScanLastError();
        Date lastRunTime = exploration ? binding.getProfileLastRunTime() : binding.getScanLastRunTime();
        if (error == null || error.isBlank() || lastRunTime == null) {
            return null;
        }
        // FAILED is the normal path; NEVER+error covers rows wiped by older status sync.
        if (status != MetadataRunStatus.FAILED && status != MetadataRunStatus.NEVER) {
            return null;
        }
        MetadataPipelineRunVO item = new MetadataPipelineRunVO();
        item.setRunId(exploration ? "local-exploration-failure" : "local-scan-failure");
        item.setPipelineType("LOCAL");
        item.setStatus(MetadataRunStatus.FAILED);
        item.setStartTime(lastRunTime);
        item.setEndTime(lastRunTime);
        item.setErrorMessage(error);
        item.setWarningsCount(0);
        return item;
    }

    private static boolean hasMatchingOmFailure(List<MetadataPipelineRunVO> runs, MetadataPipelineRunVO localFailure) {
        if (runs.isEmpty() || localFailure.getStartTime() == null) {
            return false;
        }
        Date localStart = localFailure.getStartTime();
        for (MetadataPipelineRunVO run : runs) {
            if (run.getStatus() != MetadataRunStatus.FAILED || run.getStartTime() == null) {
                continue;
            }
            // Same second-precision window used elsewhere for OM vs local clocks.
            if (Math.abs(run.getStartTime().getTime() - localStart.getTime()) <= 5_000L) {
                return true;
            }
        }
        return false;
    }

    public List<OptionVO> listDatabases(Long dataSourceId) {
        requireEnabled();
        MetadataSourceBinding binding = requireReadyBinding(dataSourceId);
        String serviceFqn = requireServiceFqn(binding, dataSourceId);
        openMetadataClient.assertFixedVersion();
        List<OptionVO> options = new ArrayList<>();
        for (OpenMetadataDatabase database : collectPages(
                after -> openMetadataClient.listDatabasesPage(serviceFqn, MAX_OM_PAGE_SIZE, after))) {
            if (!serviceFqn.equals(database.serviceFullyQualifiedName())) {
                continue;
            }
            OptionVO option = new OptionVO();
            option.setValue(database.fullyQualifiedName());
            option.setLabel(database.fullyQualifiedName());
            options.add(option);
        }
        return options;
    }

    public List<OptionVO> listSchemas(Long dataSourceId, String databaseFqn) {
        if (databaseFqn == null || databaseFqn.isBlank()) {
            throw invalid("databaseFqn");
        }
        requireEnabled();
        MetadataSourceBinding binding = requireReadyBinding(dataSourceId);
        String serviceFqn = requireServiceFqn(binding, dataSourceId);
        OpenMetadataDatabase database = openMetadataClient.findDatabase(databaseFqn)
                .orElseThrow(() -> invalid("databaseFqn does not exist"));
        if (!serviceFqn.equals(database.serviceFullyQualifiedName())) {
            throw invalid("databaseFqn does not belong to this data source");
        }
        openMetadataClient.assertFixedVersion();
        List<OptionVO> options = new ArrayList<>();
        for (OpenMetadataDatabaseSchema schema : collectPages(
                after -> openMetadataClient.listSchemasPage(databaseFqn, MAX_OM_PAGE_SIZE, after))) {
            if (!isOwnedSchema(schema, serviceFqn, databaseFqn)) {
                continue;
            }
            OptionVO option = new OptionVO();
            option.setValue(schema.getFullyQualifiedName());
            option.setLabel(schema.getName() == null || schema.getName().isBlank()
                    ? lastPart(schema.getFullyQualifiedName()) : schema.getName());
            options.add(option);
        }
        return options;
    }

    /** Invoked only by the local synchronizer after it refreshed OM truth. */
    void triggerPendingMetadataScan(MetadataSourceBinding binding) {
        if (binding == null
                || binding.getSyncStatus() != MetadataSyncStatus.READY
                || binding.getDesiredState() != MetadataDesiredState.ACTIVE
                || binding.getMetadataTriggeredVersion() == null
                || binding.getSyncedConfigVersion() == null
                || binding.getMetadataTriggeredVersion() >= binding.getSyncedConfigVersion()
                || isRunning(binding.getScanStatus())
                || isRunning(binding.getProfileStatus())) {
            return;
        }
        try {
            triggerMetadata(binding, false);
        } catch (ServiceException e) {
            log.warn("Automatic metadata scan was not triggered: dataSourceId={}", binding.getDataSourceId());
        }
    }

    /**
     * Collects sample rows for object storage after a successful metadata scan.
     *
     * <p>The storage metadata pipeline cannot collect samples and the sample agent has no
     * schedule, so nothing would ever run it. Triggering it once per completed scan keeps
     * the collection bounded and makes the operator's sample-data switch take effect on
     * the next scan, as the UI states.</p>
     */
    void triggerStorageSampleCollection(MetadataSourceBinding binding) {
        if (binding == null || !Boolean.TRUE.equals(binding.getSampleDataEnabled())) {
            return;
        }
        DataSource dataSource = dataSourceDao.queryById(binding.getDataSourceId());
        if (dataSource == null) {
            return;
        }
        MetadataConnectorAdapter adapter = connectorRegistry.find(dataSource.getDbType()).orElse(null);
        if (adapter == null || !adapter.collectsSampleDataViaAutoClassification()) {
            return;
        }
        try {
            openMetadataClient.assertFixedVersion();
            OpenMetadataEntity pipeline = openMetadataClient.upsertIngestionPipeline(
                    adapter.autoClassificationPipelineRequest(
                            MetadataStableName.autoClassificationPipelineName(binding.getDataSourceId()),
                            requireProfilerServiceId(binding, binding.getDataSourceId()),
                            requireServiceFqn(binding, binding.getDataSourceId()),
                            new MetadataSyncOptions(true, binding.getStorageManifestConfig())));
            openMetadataClient.deployIngestionPipeline(pipeline.id());
            openMetadataClient.enableIngestionPipeline(pipeline.id());
            openMetadataClient.triggerIngestionPipeline(pipeline.id());
        } catch (Exception e) {
            log.warn("Storage sample collection was not triggered: dataSourceId={}, type={}",
                    binding.getDataSourceId(), e.getClass().getSimpleName());
        }
    }

    private void triggerMetadata(MetadataSourceBinding binding, boolean manual) {
        requireEnabled();
        openMetadataClient.assertFixedVersion();
        ensureNoRunningPipeline(binding);
        long initialVersion = requireVersion(binding);
        Date now = new Date();
        Long previousTriggeredVersion = binding.getMetadataTriggeredVersion();
        Long targetTriggeredVersion = binding.getSyncedConfigVersion();
        if (!metadataBindingDao.reserveRun(
                binding.getId(), initialVersion, true, targetTriggeredVersion, now)) {
            if (manual) {
                throw invalid("a scan or exploration is already running");
            }
            return;
        }
        long reservedVersion = initialVersion + 1L;
        try {
            String pipelineId = requireMetadataPipelineId(binding, binding.getDataSourceId());
            openMetadataClient.triggerIngestionPipeline(pipelineId);
            completeReservation(binding.getId(), reservedVersion, true, targetTriggeredVersion);
        } catch (MetadataIntegrationException e) {
            failReservation(binding.getId(), reservedVersion, true, previousTriggeredVersion, e.getErrorCode());
            throw operationFailure("metadata scan could not be triggered");
        } catch (Exception e) {
            failReservation(
                    binding.getId(), reservedVersion, true, previousTriggeredVersion,
                    MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR);
            throw operationFailure("metadata scan could not be triggered");
        }
    }

    private void ensureNoRunningPipeline(MetadataSourceBinding binding) {
        if (isRunning(binding.getScanStatus()) || isRunning(binding.getProfileStatus())) {
            throw invalid("a scan or exploration is already running");
        }
        List<OpenMetadataPipelineRun> scanRuns = runs(binding.getOmMetadataPipelineFqn());
        List<OpenMetadataPipelineRun> profileRuns = runs(binding.getOmProfilerPipelineFqn());
        List<OpenMetadataPipelineRun> sampleRuns = runs(
                MetadataStableName.autoClassificationPipelineFqn(binding.getDataSourceId()));
        if (isRunning(latestStatus(scanRuns)) || isRunning(latestStatus(profileRuns))
                || isRunning(latestStatus(sampleRuns))) {
            throw invalid("a scan or exploration is already running");
        }
    }

    /** The local profile QUEUED flag belongs to the reservation being executed. */
    private void ensureNoRunningPipelineForReservedExploration(MetadataSourceBinding binding) {
        if (isRunning(binding.getScanStatus())) {
            throw invalid("a scan or exploration is already running");
        }
        List<OpenMetadataPipelineRun> scanRuns = runs(binding.getOmMetadataPipelineFqn());
        List<OpenMetadataPipelineRun> profileRuns = runs(binding.getOmProfilerPipelineFqn());
        List<OpenMetadataPipelineRun> sampleRuns = runs(
                MetadataStableName.autoClassificationPipelineFqn(binding.getDataSourceId()));
        if (isRunning(latestStatus(scanRuns)) || isRunning(latestStatus(profileRuns))
                || isRunning(latestStatus(sampleRuns))) {
            throw invalid("a scan or exploration is already running");
        }
    }

    private boolean triggerExplorationPipelines(
            MetadataConnectorAdapter adapter,
            Long dataSourceId,
            String serviceId,
            String serviceFqn,
            String databaseFqn,
            String schemaFqn,
            ExplorationReservation reservation) {
        requireCurrentExplorationReservation(reservation);
        OpenMetadataEntity profilerPipeline = openMetadataClient.upsertIngestionPipeline(
                profilerPipelineRequest(adapter,
                        MetadataStableName.profilerPipelineName(dataSourceId),
                        serviceId, serviceFqn, databaseFqn, schemaFqn));
        OpenMetadataEntity samplePipeline = openMetadataClient.upsertIngestionPipeline(
                adapter.autoClassificationPipelineRequest(
                        MetadataStableName.autoClassificationPipelineName(dataSourceId),
                        serviceId, serviceFqn, databaseFqn, schemaFqn));

        // Prepare both reusable pipelines before triggering either one. OM has no
        // transaction spanning the two triggers, so callers record partial failures.
        openMetadataClient.deployIngestionPipeline(profilerPipeline.id());
        openMetadataClient.enableIngestionPipeline(profilerPipeline.id());
        openMetadataClient.deployIngestionPipeline(samplePipeline.id());
        openMetadataClient.enableIngestionPipeline(samplePipeline.id());

        ExplorationRunBaseline baseline = captureExplorationRunBaseline(reservation);
        if (baseline == null) {
            return false;
        }
        requireCurrentExplorationReservation(reservation);
        MetadataIntegrationException profilerInitialFailure = triggerPipelineInitially(
                "PROFILER",
                profilerPipeline.id(),
                reservation);
        requireCurrentExplorationReservation(reservation);
        MetadataIntegrationException sampleInitialFailure = triggerPipelineInitially(
                "AUTO_CLASSIFICATION",
                samplePipeline.id(),
                reservation);
        triggerPipelinesWithRetry(
                profilerPipeline, baseline.profilerRunId(), profilerInitialFailure,
                samplePipeline, baseline.sampleRunId(), sampleInitialFailure,
                reservation);
        return true;
    }

    private ExplorationRunBaseline captureExplorationRunBaseline(ExplorationReservation reservation) {
        String profilerFqn = MetadataStableName.profilerPipelineFqn(reservation.dataSourceId());
        String sampleFqn = MetadataStableName.autoClassificationPipelineFqn(reservation.dataSourceId());
        String profilerBaseline = latestRunId(runs(profilerFqn));
        String sampleBaseline = latestRunId(runs(sampleFqn));
        Date baselineCapturedAt = new Date();

        for (int attempt = 0; attempt < 3; attempt++) {
            MetadataSourceBinding latest = metadataBindingDao.queryById(reservation.bindingId());
            if (!ownsExplorationReservation(latest, reservation)
                    || latest.getProfileStatus() == MetadataRunStatus.FAILED
                    || latest.getProfileStatus() == MetadataRunStatus.SUCCESS) {
                throw new IllegalStateException("exploration reservation is no longer active");
            }
            if (Boolean.TRUE.equals(latest.getProfileRunBaselineCaptured())) {
                return null;
            }
            long expectedVersion = latest.getVersion();
            latest.setProfileProfilerRunIdBaseline(profilerBaseline);
            latest.setProfileSampleRunIdBaseline(sampleBaseline);
            latest.setProfileRunBaselineCaptured(true);
            latest.setProfileRunBaselineCapturedAt(baselineCapturedAt);
            latest.setVersion(expectedVersion + 1L);
            latest.initUpdate();
            if (metadataBindingDao.updateIfVersion(latest, expectedVersion)) {
                return new ExplorationRunBaseline(profilerBaseline, sampleBaseline);
            }
        }
        throw new IllegalStateException("exploration run baseline changed concurrently");
    }

    private MetadataIntegrationException triggerPipelineInitially(
            String pipelineType,
            String pipelineId,
            ExplorationReservation reservation) {
        long startedAt = monotonicNowNanos();
        try {
            openMetadataClient.triggerIngestionPipeline(pipelineId);
            log.info(
                    "OpenMetadata pipeline trigger accepted: dataSourceId={}, pipelineType={}, attempt=1, elapsedMs={}",
                    reservation.dataSourceId(), pipelineType,
                    (monotonicNowNanos() - startedAt) / 1_000_000L);
            return null;
        } catch (MetadataIntegrationException error) {
            boolean retryable = error.getErrorCode() == MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR
                    && Integer.valueOf(400).equals(error.getHttpStatusCode());
            if (!retryable) {
                log.warn(
                        "OpenMetadata pipeline trigger failed: dataSourceId={}, pipelineType={}, attempts=1, status={}, errorCode={}, elapsedMs={}",
                        reservation.dataSourceId(), pipelineType, error.getHttpStatusCode(), error.getErrorCode(),
                        (monotonicNowNanos() - startedAt) / 1_000_000L);
                throw error;
            }
            log.warn(
                    "OpenMetadata pipeline trigger returned HTTP 400; deferring bounded retry until both initial triggers finish: dataSourceId={}, pipelineType={}, elapsedMs={}",
                    reservation.dataSourceId(), pipelineType,
                    (monotonicNowNanos() - startedAt) / 1_000_000L);
            return error;
        }
    }

    private void triggerPipelinesWithRetry(
            OpenMetadataEntity profilerPipeline,
            String profilerBaselineRunId,
            MetadataIntegrationException profilerInitialFailure,
            OpenMetadataEntity samplePipeline,
            String sampleBaselineRunId,
            MetadataIntegrationException sampleInitialFailure,
            ExplorationReservation reservation) {
        List<PendingPipelineTrigger> pending = new ArrayList<>(2);
        if (profilerInitialFailure != null) {
            pending.add(new PendingPipelineTrigger(
                    "PROFILER", profilerPipeline.id(), profilerPipeline.fullyQualifiedName(),
                    profilerBaselineRunId, profilerInitialFailure));
        }
        if (sampleInitialFailure != null) {
            pending.add(new PendingPipelineTrigger(
                    "AUTO_CLASSIFICATION", samplePipeline.id(), samplePipeline.fullyQualifiedName(),
                    sampleBaselineRunId, sampleInitialFailure));
        }
        if (pending.isEmpty()) {
            return;
        }

        long startedAt = monotonicNowNanos();
        PipelineTriggerBudget budget = pipelineTriggerBudget(reservation);
        for (int attempt = 2; !pending.isEmpty() && attempt <= MAX_PIPELINE_TRIGGER_ATTEMPTS; attempt++) {
            long delayMillis = pipelineTriggerRetryDelayMillis(attempt - 1);
            PendingPipelineTrigger firstPending = pending.get(0);
            ensureRetryWindowBudget(
                    budget, firstPending, reservation, attempt, startedAt, delayMillis);
            for (PendingPipelineTrigger retry : pending) {
                log.warn(
                        "OpenMetadata pipeline trigger retry scheduled: dataSourceId={}, pipelineType={}, attempt={}, delayMs={}, elapsedMs={}",
                        reservation.dataSourceId(), retry.pipelineType, attempt, delayMillis,
                        (monotonicNowNanos() - startedAt) / 1_000_000L);
            }
            waitBeforePipelineTriggerRetry(delayMillis);
            ensureRetryWindowBudget(budget, firstPending, reservation, attempt, startedAt, 0L);

            List<PendingPipelineTrigger> retryRound = new ArrayList<>(pending);
            if (retryRound.size() > 1 && attempt % 2 == 0) {
                // Give both pipelines a turn before either can consume the shared window.
                Collections.reverse(retryRound);
            }
            for (PendingPipelineTrigger retry : retryRound) {
                if (!pending.contains(retry)) {
                    continue;
                }
                ensurePreparationRequestBudget(
                        budget, retry, reservation, attempt, startedAt);
                requireCurrentExplorationReservation(reservation);
                if (hasPipelineRunAfterBaseline(retry.pipelineFqn, retry.baselineRunId)) {
                    log.info(
                            "OpenMetadata pipeline run appeared before retry: dataSourceId={}, pipelineType={}, attempt={}",
                            reservation.dataSourceId(), retry.pipelineType, attempt);
                    pending.remove(retry);
                    continue;
                }
                ensurePreparationRequestBudget(
                        budget, retry, reservation, attempt, startedAt);
                try {
                    openMetadataClient.triggerIngestionPipeline(retry.pipelineId);
                    log.info(
                            "OpenMetadata pipeline trigger accepted: dataSourceId={}, pipelineType={}, attempt={}, elapsedMs={}",
                            reservation.dataSourceId(), retry.pipelineType, attempt,
                            (monotonicNowNanos() - startedAt) / 1_000_000L);
                    pending.remove(retry);
                } catch (MetadataIntegrationException error) {
                    retry.lastFailure = error;
                    boolean retryable = error.getErrorCode() == MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR
                            && Integer.valueOf(400).equals(error.getHttpStatusCode());
                    if (!retryable || attempt == MAX_PIPELINE_TRIGGER_ATTEMPTS) {
                        log.warn(
                                "OpenMetadata pipeline trigger failed: dataSourceId={}, pipelineType={}, attempts={}, status={}, errorCode={}, elapsedMs={}",
                                reservation.dataSourceId(), retry.pipelineType, attempt,
                                error.getHttpStatusCode(), error.getErrorCode(),
                                (monotonicNowNanos() - startedAt) / 1_000_000L);
                        throw error;
                    }
                }
            }
        }
        if (!pending.isEmpty()) {
            PendingPipelineTrigger retry = pending.get(0);
            logPipelineTriggerBudgetExhausted(
                    retry.pipelineType, reservation, MAX_PIPELINE_TRIGGER_ATTEMPTS,
                    startedAt, retry.lastFailure);
            throw retry.lastFailure;
        }
    }

    private PipelineTriggerBudget pipelineTriggerBudget(ExplorationReservation reservation) {
        OpenMetadataRuntimeConfig runtime = configResolver.resolve();
        long preparationTimeoutSeconds = Math.max(
                0L, metadataStatusProperties.getExplorationPreparationTimeoutSeconds());
        long preparationTimeoutMillis = preparationTimeoutSeconds > Long.MAX_VALUE / 1_000L
                ? Long.MAX_VALUE
                : preparationTimeoutSeconds * 1_000L;
        long reservationAgeMillis = elapsedMillisSince(reservation.reservedAt(), System.currentTimeMillis());
        long remainingPreparationMillis = Math.max(0L, preparationTimeoutMillis - reservationAgeMillis);
        long requestTimeoutMillis = Math.max(0, runtime.getConnectTimeoutMs())
                + (long) Math.max(0, runtime.getReadTimeoutMs());
        long requestTimeoutNanos = millisToNanos(requestTimeoutMillis);
        long tailRequestBudgetNanos = Math.min(Long.MAX_VALUE, requestTimeoutNanos * 2L);
        long retryWindowSeconds = Math.max(
                0L, metadataStatusProperties.getExplorationTriggerRetryTimeoutSeconds());
        long configuredRetryWindowMillis = retryWindowSeconds > Long.MAX_VALUE / 1_000L
                ? Long.MAX_VALUE
                : retryWindowSeconds * 1_000L;
        long retryWindowNanos = millisToNanos(Math.min(
                MAX_PIPELINE_TRIGGER_RETRY_BUDGET_MILLIS, configuredRetryWindowMillis));
        long remainingPreparationNanos = millisToNanos(remainingPreparationMillis);
        long availableRetryStartNanos = Math.max(
                0L, remainingPreparationNanos - tailRequestBudgetNanos
                        - PIPELINE_TRIGGER_DEADLINE_MARGIN_MILLIS * 1_000_000L);
        long nowNanos = monotonicNowNanos();
        return new PipelineTriggerBudget(
                nowNanos + Math.min(retryWindowNanos, availableRetryStartNanos),
                nowNanos + remainingPreparationNanos,
                requestTimeoutNanos);
    }

    private void ensureRetryWindowBudget(
            PipelineTriggerBudget budget,
            PendingPipelineTrigger retry,
            ExplorationReservation reservation,
            int attempt,
            long startedAt,
            long additionalDelayMillis) {
        long remainingNanos = budget.retryStartDeadlineNanos() - monotonicNowNanos();
        long requiredNanos = PIPELINE_TRIGGER_DEADLINE_MARGIN_MILLIS * 1_000_000L
                + millisToNanos(Math.max(0L, additionalDelayMillis));
        if (remainingNanos >= requiredNanos) {
            return;
        }
        failForExhaustedPipelineTriggerBudget(retry, reservation, attempt, startedAt);
    }

    private void ensurePreparationRequestBudget(
            PipelineTriggerBudget budget,
            PendingPipelineTrigger retry,
            ExplorationReservation reservation,
            int attempt,
            long startedAt) {
        long remainingNanos = budget.preparationDeadlineNanos() - monotonicNowNanos();
        long requiredNanos = budget.requestTimeoutNanos()
                + PIPELINE_TRIGGER_DEADLINE_MARGIN_MILLIS * 1_000_000L;
        if (remainingNanos >= requiredNanos) {
            return;
        }
        failForExhaustedPipelineTriggerBudget(retry, reservation, attempt, startedAt);
    }

    private void failForExhaustedPipelineTriggerBudget(
            PendingPipelineTrigger retry,
            ExplorationReservation reservation,
            int attempt,
            long startedAt) {
        logPipelineTriggerBudgetExhausted(
                retry.pipelineType, reservation, Math.max(1, attempt - 1), startedAt, retry.lastFailure);
        if (retry.lastFailure != null) {
            throw retry.lastFailure;
        }
        throw new MetadataIntegrationException(
                MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                "OpenMetadata pipeline trigger deadline was exhausted");
    }

    private static long millisToNanos(long millis) {
        if (millis <= 0L) {
            return 0L;
        }
        return millis > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE
                : millis * 1_000_000L;
    }

    private static long elapsedMillisSince(Date start, long nowMillis) {
        if (start == null) {
            return 0L;
        }
        long elapsed;
        try {
            elapsed = Math.subtractExact(nowMillis, start.getTime());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, elapsed);
    }

    private void logPipelineTriggerBudgetExhausted(
            String pipelineType,
            ExplorationReservation reservation,
            int attempt,
            long startedAt,
            MetadataIntegrationException lastTriggerFailure) {
        log.warn(
                "OpenMetadata pipeline trigger retry budget exhausted: dataSourceId={}, pipelineType={}, attempts={}, status={}, errorCode={}, elapsedMs={}",
                reservation.dataSourceId(), pipelineType, attempt,
                lastTriggerFailure == null ? null : lastTriggerFailure.getHttpStatusCode(),
                lastTriggerFailure == null ? MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR : lastTriggerFailure.getErrorCode(),
                (monotonicNowNanos() - startedAt) / 1_000_000L);
    }

    long monotonicNowNanos() {
        return System.nanoTime();
    }

    private boolean hasPipelineRunAfterBaseline(String pipelineFqn, String baselineRunId) {
        String latestRunId = latestRunId(runs(pipelineFqn));
        return latestRunId != null && !latestRunId.equals(baselineRunId);
    }

    private static long pipelineTriggerRetryDelayMillis(int failedAttempt) {
        long delay = INITIAL_PIPELINE_TRIGGER_RETRY_DELAY_MILLIS;
        for (int i = 1; i < failedAttempt && delay < MAX_PIPELINE_TRIGGER_RETRY_DELAY_MILLIS; i++) {
            delay = Math.min(delay * 2L, MAX_PIPELINE_TRIGGER_RETRY_DELAY_MILLIS);
        }
        return delay;
    }

    void waitBeforePipelineTriggerRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                    "OpenMetadata pipeline trigger retry was interrupted",
                    interrupted);
        }
    }

    private static String latestRunId(List<OpenMetadataPipelineRun> runs) {
        return runs.stream()
                .max(Comparator.comparing(run -> run.startDate() == null
                        ? run.timestamp() == null ? 0L : run.timestamp()
                        : run.startDate()))
                .map(OpenMetadataPipelineRun::runId)
                .orElse(null);
    }

    private void requireCurrentExplorationReservation(ExplorationReservation reservation) {
        if (!ownsExplorationReservation(
                metadataBindingDao.queryById(reservation.bindingId()), reservation)) {
            throw new IllegalStateException("exploration reservation has been superseded");
        }
    }

    private static boolean ownsExplorationReservation(
            MetadataSourceBinding binding, ExplorationReservation reservation) {
        return binding != null
                && binding.getVersion() != null
                && isReservationRun(binding.getProfileLastRunTime(), reservation.reservedAt())
                && binding.getProfileStatus() != MetadataRunStatus.SUCCESS
                && binding.getProfileStatus() != MetadataRunStatus.FAILED
                && reservation.reservationToken() != null
                && !reservation.reservationToken().isBlank()
                && reservation.reservationToken().equals(binding.getProfileRunReservationToken());
    }

    private List<OpenMetadataPipelineRun> runs(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return List.of();
        }
        return openMetadataClient.listIngestionPipelineRuns(fqn, 1);
    }

    private void completeReservation(Long bindingId, long reservedVersion, boolean metadataScan, Long triggeredVersion) {
        MetadataSourceBinding latest = metadataBindingDao.queryById(bindingId);
        if (latest == null || !Long.valueOf(reservedVersion).equals(latest.getVersion())) {
            return;
        }
        if (metadataScan && triggeredVersion != null) {
            latest.setMetadataTriggeredVersion(triggeredVersion);
        }
        latest.setVersion(reservedVersion + 1L);
        latest.initUpdate();
        metadataBindingDao.updateIfVersion(latest, reservedVersion);
    }

    private void completeExplorationReservation(ExplorationReservation reservation) {
        for (int attempt = 0; attempt < 3; attempt++) {
            MetadataSourceBinding latest = metadataBindingDao.queryById(reservation.bindingId());
            if (latest == null || latest.getVersion() == null
                    || !ownsExplorationReservation(latest, reservation)) {
                return;
            }
            if (latest.getProfileStatus() == MetadataRunStatus.SUCCESS
                    || latest.getProfileStatus() == MetadataRunStatus.FAILED) {
                return;
            }
            long expectedVersion = latest.getVersion();
            latest.setProfileStatus(MetadataRunStatus.RUNNING);
            // Run-registration grace starts after both trigger control-plane calls complete.
            latest.setProfileRunBaselineCapturedAt(new Date());
            latest.setProfileLastError(null);
            latest.setVersion(expectedVersion + 1L);
            latest.initUpdate();
            if (metadataBindingDao.updateIfVersion(latest, expectedVersion)) {
                return;
            }
        }
        log.warn("Could not persist running exploration state after concurrent updates: dataSourceId={}",
                reservation.dataSourceId());
    }

    private void failReservation(
            Long bindingId,
            long reservedVersion,
            boolean metadataScan,
            Long previousTriggeredVersion,
            MetadataErrorCode errorCode) {
        MetadataSourceBinding latest = metadataBindingDao.queryById(bindingId);
        if (latest == null || !Long.valueOf(reservedVersion).equals(latest.getVersion())) {
            return;
        }
        if (metadataScan) {
            latest.setScanStatus(MetadataRunStatus.FAILED);
            latest.setScanLastError(errorCode.name());
            latest.setMetadataTriggeredVersion(previousTriggeredVersion);
        } else {
            latest.setProfileStatus(MetadataRunStatus.FAILED);
            latest.setProfileLastError(errorCode.name());
        }
        latest.setVersion(reservedVersion + 1L);
        latest.initUpdate();
        metadataBindingDao.updateIfVersion(latest, reservedVersion);
    }

    private void failExplorationReservation(
            ExplorationReservation reservation, MetadataErrorCode errorCode) {
        for (int attempt = 0; attempt < 3; attempt++) {
            MetadataSourceBinding latest = metadataBindingDao.queryById(reservation.bindingId());
            if (latest == null || latest.getVersion() == null) {
                return;
            }
            Date lastRunTime = latest.getProfileLastRunTime();
            if (!ownsExplorationReservation(latest, reservation)
                    || latest.getProfileStatus() == MetadataRunStatus.SUCCESS
                    || latest.getProfileStatus() == MetadataRunStatus.FAILED) {
                return;
            }
            long expectedVersion = latest.getVersion();
            latest.setProfileStatus(MetadataRunStatus.FAILED);
            latest.setProfileLastError(errorCode.name());
            latest.setVersion(expectedVersion + 1L);
            latest.initUpdate();
            if (metadataBindingDao.updateIfVersion(latest, expectedVersion)) {
                invalidateCachesAfterExplorationFailure(latest);
                return;
            }
        }
        log.warn("Could not persist failed exploration state after concurrent updates: dataSourceId={}",
                reservation.dataSourceId());
    }

    private void invalidateCachesAfterExplorationFailure(MetadataSourceBinding binding) {
        try {
            String serviceFqn = requireServiceFqn(binding, binding.getDataSourceId());
            omReadCache.invalidateService(serviceFqn, false, true);
        } catch (Exception e) {
            log.warn("Could not invalidate OpenMetadata profile cache after exploration failure: dataSourceId={}, type={}",
                    binding.getDataSourceId(), e.getClass().getSimpleName());
        }
        if (metadataInventoryCache != null) {
            try {
                metadataInventoryCache.invalidateAllSnapshots();
            } catch (Exception e) {
                log.warn("Could not invalidate metadata inventory after exploration failure: dataSourceId={}, type={}",
                        binding.getDataSourceId(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * MySQL Date columns in the metadata binding table are second-precision in
     * some deployments, while the reservation is created with millisecond
     * precision. Allow that storage truncation without accepting an older run.
     */
    private static boolean isReservationRun(Date lastRunTime, Date reservedAt) {
        return lastRunTime != null
                && reservedAt != null
                && lastRunTime.getTime() >= reservedAt.getTime() - RESERVATION_TIME_TOLERANCE_MILLIS;
    }

    private MetadataSourceBinding requireReadyBinding(Long dataSourceId) {
        requireActiveDataSource(dataSourceId);
        MetadataSourceBinding binding = requireBinding(dataSourceId);
        if (binding.getDesiredState() != MetadataDesiredState.ACTIVE || binding.getSyncStatus() != MetadataSyncStatus.READY) {
            throw invalid("metadata synchronization is not ready");
        }
        return binding;
    }

    private MetadataSourceBinding requireBinding(Long dataSourceId) {
        if (dataSourceId == null || dataSourceId <= 0) {
            throw invalid("dataSourceId");
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null) {
            throw invalid("metadata binding is not initialized");
        }
        return binding;
    }

    /** True when the adapter of this data source can collect OpenMetadata sample data. */
    private boolean supportsSampleData(Long dataSourceId) {
        DataSource source = dataSourceDao.queryById(dataSourceId);
        if (source == null) {
            return false;
        }
        return connectorRegistry.find(source.getDbType())
                .map(MetadataConnectorAdapter::supportsSampleData)
                .orElse(false);
    }

    /** True when the adapter of this data source derives containers from a manifest. */
    private boolean supportsStorageManifest(Long dataSourceId) {
        DataSource source = dataSourceDao.queryById(dataSourceId);
        if (source == null) {
            return false;
        }
        return connectorRegistry.find(source.getDbType())
                .map(MetadataConnectorAdapter::supportsStorageManifest)
                .orElse(false);
    }

    private DataSource requireActiveDataSource(Long dataSourceId) {
        if (dataSourceId == null || dataSourceId <= 0) {
            throw invalid("dataSourceId");
        }
        DataSource source = dataSourceDao.queryById(dataSourceId);
        if (source == null || source.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw invalid("data source is unavailable");
        }
        return source;
    }

    /**
     * Exploration is a table-scoped OpenMetadata capability. Rejecting it here keeps
     * non-relational sources (Kafka, S3, SFTP, HTTP, Elasticsearch) from reserving a
     * run that can only fail later inside the profiler pipeline request.
     *
     * <p>A data source type without any connector is left to the caller: such bindings
     * never reach READY, so the binding gate already blocks them.</p>
     */
    private DataSource requireProfilerCapableDataSource(Long dataSourceId) {
        DataSource source = requireActiveDataSource(dataSourceId);
        connectorRegistry.find(source.getDbType())
                .filter(adapter -> !adapter.supportsProfiler())
                .ifPresent(adapter -> {
                    throw invalid("exploration is not supported for this data source type");
                });
        return source;
    }

    private void requireEnabled() {
        if (!configResolver.isEnabled()) {
            throw invalid("OpenMetadata integration is disabled");
        }
    }

    private static String requireMetadataPipelineId(MetadataSourceBinding binding, Long dataSourceId) {
        if (binding.getOmMetadataPipelineId() == null || binding.getOmMetadataPipelineId().isBlank()) {
            throw invalid("metadata pipeline has not been synchronized for dataSourceId=" + dataSourceId);
        }
        return binding.getOmMetadataPipelineId();
    }

    private static String requireProfilerServiceId(MetadataSourceBinding binding, Long dataSourceId) {
        if (binding.getOmServiceId() == null || binding.getOmServiceId().isBlank()) {
            throw invalid("OpenMetadata service has not been synchronized for dataSourceId=" + dataSourceId);
        }
        return binding.getOmServiceId();
    }

    private static String requireServiceFqn(MetadataSourceBinding binding, Long dataSourceId) {
        if (binding.getOmServiceFqn() == null || binding.getOmServiceFqn().isBlank()) {
            return MetadataStableName.serviceFqn(dataSourceId);
        }
        return binding.getOmServiceFqn();
    }

    private void requireOwnedSchema(String serviceFqn, String databaseFqn, String schemaFqn) {
        if (schemaFqn == null || schemaFqn.isBlank()) {
            return;
        }
        boolean owned = collectPages(
                after -> openMetadataClient.listSchemasPage(databaseFqn, MAX_OM_PAGE_SIZE, after))
                .stream()
                .anyMatch(schema -> schemaFqn.equals(schema == null ? null : schema.getFullyQualifiedName())
                        && isOwnedSchema(schema, serviceFqn, databaseFqn));
        if (!owned) {
            throw invalid("schemaFqn does not belong to this data source database");
        }
    }

    private static boolean isOwnedSchema(
            OpenMetadataDatabaseSchema schema, String serviceFqn, String databaseFqn) {
        if (schema == null || schema.getFullyQualifiedName() == null
                || !databaseFqn.equals(schema.getDatabaseFullyQualifiedName())) {
            return false;
        }
        String schemaServiceFqn = schema.getServiceFullyQualifiedName();
        return schemaServiceFqn == null || schemaServiceFqn.isBlank() || serviceFqn.equals(schemaServiceFqn);
    }

    private static JsonNode profilerPipelineRequest(
            MetadataConnectorAdapter adapter,
            String pipelineName,
            String serviceId,
            String serviceFqn,
            String databaseFqn,
            String schemaFqn) {
        if (schemaFqn == null || schemaFqn.isBlank()) {
            return adapter.profilerPipelineRequest(pipelineName, serviceId, serviceFqn, databaseFqn);
        }
        return adapter.profilerPipelineRequest(
                pipelineName, serviceId, serviceFqn, databaseFqn, schemaFqn);
    }

    private static String lastPart(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    static boolean isRunning(MetadataRunStatus status) {
        return status == MetadataRunStatus.QUEUED || status == MetadataRunStatus.RUNNING;
    }

    private static MetadataRunStatus latestStatus(List<OpenMetadataPipelineRun> runs) {
        return runs.stream()
                .max(Comparator.comparing(run -> firstNonNull(run.startDate(), run.timestamp(), 0L)))
                .map(run -> OpenMetadataRunStatusMapper.fromPipelineState(run.pipelineState()))
                .orElse(MetadataRunStatus.NEVER);
    }

    private static <T> List<T> collectPages(Function<String, OpenMetadataPage<T>> loader) {
        List<T> result = new ArrayList<>();
        String after = null;
        Set<String> seen = new HashSet<>();
        for (int pageNumber = 0; pageNumber < MAX_OM_PAGES; pageNumber++) {
            OpenMetadataPage<T> page = loader.apply(after);
            if (page == null) {
                break;
            }
            result.addAll(page.data() == null ? List.of() : page.data());
            String next = page.after();
            if (next == null || next.isBlank() || !seen.add(next)) {
                break;
            }
            after = next;
        }
        return result;
    }

    private static MetadataRunStateVO runState(
            MetadataRunStatus status, Date lastRunTime, Date lastSuccessTime, String lastError) {
        MetadataRunStateVO state = new MetadataRunStateVO();
        state.setStatus(status == null ? MetadataRunStatus.NEVER : status);
        state.setLastRunTime(lastRunTime);
        state.setLastSuccessTime(lastSuccessTime);
        state.setLastError(lastError);
        return state;
    }

    static Date fromOmTimestamp(Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            return null;
        }
        long millis = timestamp < 100_000_000_000L ? timestamp * 1000L : timestamp;
        return Date.from(Instant.ofEpochMilli(millis));
    }

    private static Long firstNonNull(Long first, Long second) {
        return first != null ? first : second;
    }

    private static Long firstNonNull(Long first, Long second, Long third) {
        return first != null ? first : second != null ? second : third;
    }

    private static long requireVersion(MetadataSourceBinding binding) {
        if (binding.getVersion() == null) {
            throw invalid("metadata binding has no optimistic version");
        }
        return binding.getVersion();
    }

    private static ServiceException invalid(String reason) {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, reason);
    }

    private static ServiceException operationFailure(String message) {
        return new ServiceException(Status.INTERNAL_SERVER_ERROR_ARGS, message);
    }
}
