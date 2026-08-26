package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorAdapter;
import org.apache.seatunnel.web.api.metadata.adapter.MetadataConnectorRegistry;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataEntity;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPipelineRun;
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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * User and scheduler operations on the existing DataSource binding. Every external
 * request goes through OpenMetadata Server 1.12.10; no Airflow client exists here.
 */
@Slf4j
@Service
public class MetadataPipelineOperationService {

    private static final int MAX_OM_PAGE_SIZE = 1000;
    private static final int MAX_OM_PAGES = 10_000;

    private final OpenMetadataProperties openMetadataProperties;
    private final MetadataBindingDao metadataBindingDao;
    private final DataSourceDao dataSourceDao;
    private final MetadataConnectorRegistry connectorRegistry;
    private final OpenMetadataClient openMetadataClient;

    public MetadataPipelineOperationService(
            OpenMetadataProperties openMetadataProperties,
            MetadataBindingDao metadataBindingDao,
            DataSourceDao dataSourceDao,
            MetadataConnectorRegistry connectorRegistry,
            OpenMetadataClient openMetadataClient) {
        this.openMetadataProperties = openMetadataProperties;
        this.metadataBindingDao = metadataBindingDao;
        this.dataSourceDao = dataSourceDao;
        this.connectorRegistry = connectorRegistry;
        this.openMetadataClient = openMetadataClient;
    }

    public boolean triggerScan(Long dataSourceId) {
        MetadataSourceBinding binding = requireReadyBinding(dataSourceId);
        triggerMetadata(binding, true);
        return true;
    }

    public boolean triggerExploration(Long dataSourceId, String databaseFqn) {
        if (databaseFqn == null || databaseFqn.isBlank()) {
            throw invalid("databaseFqn");
        }
        MetadataSourceBinding binding = requireReadyBinding(dataSourceId);
        DataSource dataSource = requireActiveDataSource(dataSourceId);
        String serviceFqn = requireServiceFqn(binding, dataSourceId);
        OpenMetadataDatabase database = openMetadataClient.findDatabase(databaseFqn)
                .orElseThrow(() -> invalid("databaseFqn does not exist"));
        if (!serviceFqn.equals(database.serviceFullyQualifiedName())) {
            throw invalid("databaseFqn does not belong to this data source");
        }
        openMetadataClient.assertFixedVersion();
        ensureNoRunningPipeline(binding);
        long initialVersion = requireVersion(binding);
        Date now = new Date();
        if (!metadataBindingDao.reserveRun(binding.getId(), initialVersion, false, null, now)) {
            throw invalid("a scan or exploration is already running");
        }
        long reservedVersion = initialVersion + 1L;
        try {
            MetadataConnectorAdapter adapter = connectorRegistry.require(dataSource.getDbType());
            OpenMetadataEntity pipeline = openMetadataClient.upsertIngestionPipeline(
                    adapter.profilerPipelineRequest(
                            MetadataStableName.profilerPipelineName(dataSourceId),
                            requireProfilerServiceId(binding, dataSourceId),
                            serviceFqn,
                            databaseFqn));
            openMetadataClient.deployIngestionPipeline(pipeline.id());
            openMetadataClient.triggerIngestionPipeline(pipeline.id());
            completeReservation(binding.getId(), reservedVersion, false, null);
            return true;
        } catch (MetadataIntegrationException e) {
            failReservation(binding.getId(), reservedVersion, false, null, e.getErrorCode());
            throw operationFailure("data-source exploration could not be triggered");
        } catch (Exception e) {
            failReservation(binding.getId(), reservedVersion, false, null, MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR);
            throw operationFailure("data-source exploration could not be triggered");
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
        if (binding == null) {
            status.setSyncStatus("NOT_INITIALIZED");
            status.setScan(runState(MetadataRunStatus.NEVER, null, null, null));
            status.setExploration(runState(MetadataRunStatus.NEVER, null, null, null));
            return status;
        }
        status.setSyncStatus(binding.getSyncStatus() == null ? "NOT_INITIALIZED" : binding.getSyncStatus().name());
        status.setScan(runState(
                binding.getScanStatus(), binding.getScanLastRunTime(), binding.getScanLastSuccessTime(), binding.getScanLastError()));
        status.setExploration(runState(
                binding.getProfileStatus(),
                binding.getProfileLastRunTime(),
                binding.getProfileLastSuccessTime(),
                binding.getProfileLastError()));
        return status;
    }

    public List<MetadataPipelineRunVO> listRuns(Long dataSourceId, String type, int limit) {
        requireEnabled();
        MetadataSourceBinding binding = requireReadyBinding(dataSourceId);
        boolean exploration = "EXPLORATION".equalsIgnoreCase(type);
        if (!exploration && !"SCAN".equalsIgnoreCase(type)) {
            throw invalid("type must be SCAN or EXPLORATION");
        }
        String fqn = exploration ? binding.getOmProfilerPipelineFqn() : binding.getOmMetadataPipelineFqn();
        if (fqn == null || fqn.isBlank()) {
            throw invalid("pipeline has not been synchronized");
        }
        openMetadataClient.assertFixedVersion();
        List<MetadataPipelineRunVO> result = new ArrayList<>();
        for (OpenMetadataPipelineRun run : openMetadataClient.listIngestionPipelineRuns(fqn, limit)) {
            MetadataPipelineRunVO item = new MetadataPipelineRunVO();
            item.setRunId(run.runId());
            item.setStatus(OpenMetadataRunStatusMapper.fromPipelineState(run.pipelineState()));
            item.setStartTime(fromOmTimestamp(firstNonNull(run.startDate(), run.timestamp())));
            item.setEndTime(fromOmTimestamp(run.endDate()));
            item.setWarningsCount(run.warningsCount());
            result.add(item);
        }
        return result;
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
        if (isRunning(latestStatus(scanRuns)) || isRunning(latestStatus(profileRuns))) {
            throw invalid("a scan or exploration is already running");
        }
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

    private void requireEnabled() {
        if (!openMetadataProperties.isEnabled()) {
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

    static boolean isRunning(MetadataRunStatus status) {
        return status == MetadataRunStatus.QUEUED || status == MetadataRunStatus.RUNNING;
    }

    private static MetadataRunStatus latestStatus(List<OpenMetadataPipelineRun> runs) {
        return runs.stream()
                .max(Comparator.comparing(run -> firstNonNull(run.timestamp(), run.startDate(), 0L)))
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
