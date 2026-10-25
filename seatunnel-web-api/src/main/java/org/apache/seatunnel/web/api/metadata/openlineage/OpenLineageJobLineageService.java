package org.apache.seatunnel.web.api.metadata.openlineage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.alarm.event.JobStatusChangedEvent;
import org.apache.seatunnel.web.api.metadata.FieldMappingExtractor;
import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageEventFactory.EventType;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes SeaTunnel job lineage to Kafka as OpenLineage {@code RunEvent} JSON.
 *
 * <p>
 * This is the only lineage channel of the web: it fetches the job instances, runtime configs and
 * datasource names itself, uses the local names as dataset identity (the asset project resolves
 * them to OpenMetadata entities) and writes nothing to OpenMetadata itself. Whole-run life cycle
 * per the bridge design v1: one START at submission acceptance, periodic RUNNING heartbeats for
 * streaming jobs, exactly one terminal COMPLETE / FAIL / ABORT per run. The runId is derived
 * deterministically from the instance id and the engine job id, so START and the terminal event
 * share one runId even across a web restart, and a savepoint-restored run that reuses the previous
 * engine job id still gets its own runId.
 * </p>
 *
 * <p>
 * Terminal handling per running model (both confirmed against the code):
 * Batch jobs: one {@link JobStatusChangedEvent} per final status is published by the batch
 * result handler, consumed here. A batch instance that disappears without that event (the
 * engine-unavailable bulk failure path) is reported by the batch pulse. Streaming jobs do not
 * publish that event (its alarm lookup only
 * knows the batch table), so the pulse below detects a stream terminal when a previously tracked
 * instance disappears from the running-like set, and maps the status it actually reached.
 * </p>
 */
@Service
@ConditionalOnProperty(prefix = "lineage-bridge.kafka", name = "enabled", havingValue = "true")
@Slf4j
public class OpenLineageJobLineageService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** How many terminal publications are remembered before the oldest ones are forgotten. */
    private static final int TERMINAL_PUBLISHED_CAPACITY = 4096;

    /**
     * Outcome of one publication attempt. {@link #SKIPPED} means the event is not publishable at
     * all (no identifiable dataset), {@link #FAILED} means it could be published but was not
     * delivered, so the caller has to try again.
     */
    private enum PublishOutcome {
        PUBLISHED,
        SKIPPED,
        FAILED
    }

    private final LineageBridgeProperties properties;
    private final OpenLineageKafkaBridge bridge;
    private final JobInstanceDao jobInstanceDao;
    private final StreamingJobInstanceDao streamingJobInstanceDao;
    private final DataSourceDao dataSourceDao;

    /**
     * Streaming instances observed in the running-like set, with the status last seen
     * ({@code id → jobStatus}). Disappearance from the set is the only streaming terminal
     * signal the web currently has.
     */
    private final Map<Long, String> monitoredStreamingStatuses = new ConcurrentHashMap<>();

    /**
     * Batch instances observed in the running-like set. Normal batch terminals arrive through
     * {@link JobStatusChangedEvent}; the pulse is the fallback for a batch instance that the web
     * changes to a terminal status without publishing that event (the engine-unavailable bulk
     * failure path).
     */
    private final Map<Long, String> monitoredBatchStatuses = new ConcurrentHashMap<>();

    /**
     * Instances whose terminal event was already published, so the pulse and the event agree.
     *
     * <p>
     * The set is bounded: an instance that left the running set is forgotten as soon as its
     * terminal was dealt with, so an unbounded set would grow with every job the web ever ran.
     * </p>
     */
    private final Set<String> terminalPublished = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > TERMINAL_PUBLISHED_CAPACITY;
                }
            }));

    /**
     * Terminal events the broker did not acknowledge. The pulse publishes them again, so a
     * terminal survives a broker outage even when the instance was never seen in the running set.
     *
     * <p>
     * The map is bounded: an instance whose terminal can never be delivered is forgotten instead
     * of being retried for the whole life of the web.
     * </p>
     */
    private final Map<Long, OpenLineageSnapshot> pendingTerminals =
            Collections.synchronizedMap(new LinkedHashMap<Long, OpenLineageSnapshot>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, OpenLineageSnapshot> eldest) {
                    return size() > TERMINAL_PUBLISHED_CAPACITY;
                }
            });

    OpenLineageJobLineageService(LineageBridgeProperties properties,
                                 OpenLineageKafkaBridge bridge,
                                 JobInstanceDao jobInstanceDao,
                                 StreamingJobInstanceDao streamingJobInstanceDao,
                                 DataSourceDao dataSourceDao) {
        this.properties = properties;
        this.bridge = bridge;
        this.jobInstanceDao = jobInstanceDao;
        this.streamingJobInstanceDao = streamingJobInstanceDao;
        this.dataSourceDao = dataSourceDao;
    }

    /**
     * START of one run, called by the submitters after the Engine accepted the job. Async on the
     * independent lineage track: the Engine has accepted the job, so this must never extend the
     * submission response.
     */
    @Async("lineageEventExecutor")
    public void recordStart(JobInstanceVO submittedInstance, String engineJobId, boolean streaming) {
        if (submittedInstance == null
                || submittedInstance.getId() == null
                || StringUtils.isBlank(engineJobId)) {
            return;
        }
        try {
            JobInstanceVO details = loadDetails(submittedInstance, streaming);
            publishIfValid(toStartSnapshot(details, engineJobId, streaming));
        } catch (Exception error) {
            log.warn("Could not publish OpenLineage START event, instanceId={}, engineJobId={}",
                    submittedInstance.getId(), engineJobId, error);
        }
    }

    /**
     * Terminal of one batch run. The batch result handler publishes the final status
     * (FINISHED / FAILED / CANCELED); every other status (STARTING, transitions, ...)
     * emits no event because the run either never started or cannot be pinned to a final state.
     */
    @Async("lineageEventExecutor")
    @EventListener
    public void onBatchJobStatusChanged(JobStatusChangedEvent event) {
        if (event == null || event.getJobInstanceId() == null || event.getNewStatus() == null) {
            return;
        }
        EventType eventType = batchEventType(event.getNewStatus());
        if (eventType == null) {
            if (event.getNewStatus() == JobStatus.STARTING) {
                log.debug("OpenLineage bridge skips {} without an event, instanceId={}",
                        event.getNewStatus(), event.getJobInstanceId());
            }
            return;
        }
        try {
            JobInstanceVO details = jobInstanceDao.selectDetailById(event.getJobInstanceId());
            if (details == null) {
                log.debug("OpenLineage bridge skips terminal for an unknown instance, instanceId={}",
                        event.getJobInstanceId());
                return;
            }
            String engineJobId = StringUtils.defaultIfBlank(
                    event.getEngineJobId(), details.getEngineJobId());
            if (StringUtils.isBlank(engineJobId)) {
                log.debug("OpenLineage bridge skips terminal without an engine job id, instanceId={}",
                        event.getJobInstanceId());
                return;
            }
            String prevStatus = event.getOldStatus() == null
                    ? monitoredBatchStatuses.get(event.getJobInstanceId())
                    : event.getOldStatus().name();
            OpenLineageSnapshot snapshot = toSnapshot(details, engineJobId, false, eventType,
                    prevStatus, event.getErrorMessage(), null);
            String key = terminalKey(false, event.getJobInstanceId());
            if (!terminalPublished.add(key)) {
                log.debug("OpenLineage terminal already published, instanceId={}",
                        event.getJobInstanceId());
                return;
            }
            PublishOutcome outcome = publishIfValid(snapshot);
            if (outcome == PublishOutcome.FAILED) {
                // The broker did not acknowledge the event: the pulse publishes the terminal
                // again. A skipped event has no identifiable dataset and would never be
                // publishable, so it is not retried.
                terminalPublished.remove(key);
                pendingTerminals.put(event.getJobInstanceId(), snapshot);
            }
        } catch (Exception error) {
            log.warn("Could not publish OpenLineage terminal event, instanceId={}, status={}",
                    event.getJobInstanceId(), event.getNewStatus(), error);
        }
    }

    /**
     * Periodic pulse for streaming jobs: RUNNING heartbeats with the same lineage as START, and
     * terminal detection via vanishing from the running set.
     *
     * <p>
     * The pulse runs on the lineage track, not on the shared scheduler: a broker that is slow or
     * unreachable must not delay the job metrics, the metadata status refresh and the recovery
     * scheduler, which share that single scheduler thread.
     * </p>
     */
    @Async("lineageEventExecutor")
    @Scheduled(fixedDelayString = "${lineage-bridge.running.interval-ms:60000}")
    public void pulseStreamingJobs() {
        try {
            List<JobInstanceVO> running = streamingJobInstanceDao.listRunning();
            Set<Long> present = new HashSet<>();
            for (JobInstanceVO listed : running) {
                if (listed == null || listed.getId() == null) {
                    continue;
                }
                present.add(listed.getId());
                if ("RUNNING".equalsIgnoreCase(listed.getJobStatus())) {
                    publishHeartbeat(listed);
                }
            }

            Map<Long, String> vanished = vanished(monitoredStreamingStatuses, present);
            monitoredStreamingStatuses.keySet().retainAll(present);
            for (JobInstanceVO listed : running) {
                if (listed != null && listed.getId() != null) {
                    monitoredStreamingStatuses.put(listed.getId(), listed.getJobStatus());
                }
            }
            for (Map.Entry<Long, String> entry : vanished.entrySet()) {
                if (publishStreamingTerminal(entry.getKey(), entry.getValue())
                        == PublishOutcome.FAILED) {
                    // The terminal was not delivered: keep the instance monitored so that the
                    // next pulse publishes it again. A lost terminal leaves the run open on the
                    // asset side forever.
                    monitoredStreamingStatuses.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception error) {
            log.warn("OpenLineage streaming pulse failed", error);
        }
    }

    /**
     * Periodic pulse for batch jobs. A batch terminal normally arrives as a
     * {@link JobStatusChangedEvent}; when the web marks instances FAILED in bulk because the
     * Engine client died, no such event is published. A vanished batch instance is therefore
     * reported here, deduplicated against the event path by {@link #terminalPublished}.
     */
    @Async("lineageEventExecutor")
    @Scheduled(fixedDelayString = "${lineage-bridge.running.interval-ms:60000}")
    public void pulseBatchJobs() {
        try {
            List<JobInstance> running = jobInstanceDao.listRunningLikeInstances();
            Set<Long> present = new HashSet<>();
            Map<Long, String> statuses = new HashMap<>();
            if (running != null) {
                for (JobInstance instance : running) {
                    if (instance != null && instance.getId() != null) {
                        present.add(instance.getId());
                        statuses.put(
                                instance.getId(),
                                instance.getJobStatus() == null
                                        ? null
                                        : instance.getJobStatus().name());
                    }
                }
            }

            Map<Long, String> vanished = vanished(monitoredBatchStatuses, present);
            monitoredBatchStatuses.keySet().retainAll(present);
            monitoredBatchStatuses.putAll(statuses);
            for (Map.Entry<Long, String> entry : vanished.entrySet()) {
                if (publishBatchTerminal(entry.getKey(), entry.getValue())
                        == PublishOutcome.FAILED) {
                    // The terminal was not delivered: keep the instance monitored so that the
                    // next pulse publishes it again.
                    monitoredBatchStatuses.put(entry.getKey(), entry.getValue());
                }
            }
            retryPendingTerminals();
        } catch (Exception error) {
            log.warn("OpenLineage batch pulse failed", error);
        }
    }

    /**
     * Publishes the terminal events the broker did not acknowledge. The event path may see a
     * job that finished before the pulse ever observed it, so the pending terminals are kept
     * until the broker confirms them.
     */
    private void retryPendingTerminals() {
        List<Map.Entry<Long, OpenLineageSnapshot>> entries;
        synchronized (pendingTerminals) {
            if (pendingTerminals.isEmpty()) {
                return;
            }
            entries = new ArrayList<>(pendingTerminals.entrySet());
        }
        for (Map.Entry<Long, OpenLineageSnapshot> entry : entries) {
            Long instanceId = entry.getKey();
            String key = terminalKey(false, instanceId);
            if (!terminalPublished.add(key)) {
                continue;
            }
            if (publishIfValid(entry.getValue()) != PublishOutcome.PUBLISHED) {
                terminalPublished.remove(key);
                continue;
            }
            pendingTerminals.remove(instanceId);
        }
    }

    /** The monitored instances that are no longer in the running set, with their last status. */
    private static Map<Long, String> vanished(Map<Long, String> monitored, Set<Long> present) {
        Map<Long, String> vanished = new HashMap<>();
        for (Map.Entry<Long, String> entry : monitored.entrySet()) {
            if (!present.contains(entry.getKey())) {
                vanished.put(entry.getKey(), entry.getValue());
            }
        }
        return vanished;
    }

    private PublishOutcome publishBatchTerminal(Long instanceId, String lastSeenStatus) {
        try {
            JobInstanceVO details = jobInstanceDao.selectDetailById(instanceId);
            if (details == null) {
                return PublishOutcome.SKIPPED;
            }
            EventType eventType = batchTerminalType(details.getJobStatus());
            if (eventType == null) {
                log.debug("OpenLineage bridge drops vanished batch instance with status={}",
                        details.getJobStatus());
                return PublishOutcome.SKIPPED;
            }
            if (StringUtils.isBlank(details.getEngineJobId())) {
                return PublishOutcome.SKIPPED;
            }
            OpenLineageSnapshot snapshot = toSnapshot(details, details.getEngineJobId(), false,
                    eventType, lastSeenStatus, details.getErrorMessage(), null);
            String key = terminalKey(false, instanceId);
            if (!terminalPublished.add(key)) {
                return PublishOutcome.PUBLISHED;
            }
            PublishOutcome outcome = publishIfValid(snapshot);
            if (outcome != PublishOutcome.PUBLISHED) {
                terminalPublished.remove(key);
            }
            return outcome;
        } catch (Exception error) {
            log.warn("Could not publish OpenLineage batch terminal, instanceId={}", instanceId, error);
            return PublishOutcome.FAILED;
        }
    }

    /** Batch terminal mapping from the persisted status name. */
    private static EventType batchTerminalType(String status) {
        if (StringUtils.isBlank(status)) {
            return null;
        }
        try {
            return batchEventType(JobStatus.valueOf(status.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private void publishHeartbeat(JobInstanceVO listed) {
        try {
            JobInstanceVO details = streamingJobInstanceDao.selectDetailById(listed.getId());
            if (details == null || StringUtils.isBlank(details.getEngineJobId())) {
                return;
            }
            publishIfValid(toSnapshot(details, details.getEngineJobId(), true, EventType.RUNNING,
                    null, null, Instant.now().toString()));
        } catch (Exception error) {
            log.warn("Could not publish OpenLineage RUNNING heartbeat, instanceId={}",
                    listed.getId(), error);
        }
    }

    private PublishOutcome publishStreamingTerminal(Long instanceId, String lastSeenStatus) {
        try {
            JobInstanceVO details = streamingJobInstanceDao.selectDetailById(instanceId);
            if (details == null) {
                // The row is gone (definition deleted); there is no run left to report.
                return PublishOutcome.SKIPPED;
            }
            EventType eventType = streamingTerminalType(details.getJobStatus());
            if (eventType == null) {
                log.debug("OpenLineage bridge drops vanished streaming instance with status={}",
                        details.getJobStatus());
                return PublishOutcome.SKIPPED;
            }
            if (StringUtils.isBlank(details.getEngineJobId())) {
                return PublishOutcome.SKIPPED;
            }
            OpenLineageSnapshot snapshot = toSnapshot(details, details.getEngineJobId(), true,
                    eventType, lastSeenStatus, details.getErrorMessage(), null);
            String key = terminalKey(true, instanceId);
            if (!terminalPublished.add(key)) {
                return PublishOutcome.PUBLISHED;
            }
            PublishOutcome outcome = publishIfValid(snapshot);
            if (outcome != PublishOutcome.PUBLISHED) {
                terminalPublished.remove(key);
            }
            return outcome;
        } catch (Exception error) {
            log.warn("Could not publish OpenLineage streaming terminal, instanceId={}", instanceId, error);
            return PublishOutcome.FAILED;
        }
    }

    private static String terminalKey(boolean streaming, Long instanceId) {
        return (streaming ? "stream:" : "batch:") + instanceId;
    }

    /** Batch terminal mapping per bridge design 4.2. */
    private static EventType batchEventType(JobStatus status) {
        switch (status) {
            case FINISHED:
                return EventType.COMPLETE;
            case FAILED:
                return EventType.FAIL;
            case CANCELED:
            case SAVEPOINT_DONE:
                return EventType.ABORT;
            default:
                // STARTING / UNKNOWABLE / transitions: no event, log only.
                return null;
        }
    }

    /**
     * Streaming terminal mapping. A streaming run that the Engine finished normally completed
     * successfully; a canceled or savepoint-stopped run is an abort. FAIL stays FAIL.
     */
    private static EventType streamingTerminalType(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "FINISHED" -> EventType.COMPLETE;
            case "FAILED" -> EventType.FAIL;
            case "CANCELED", "CANCELLED", "STOPPED", "SAVEPOINT_DONE" -> EventType.ABORT;
            default -> null;
        };
    }

    private JobInstanceVO loadDetails(JobInstanceVO submittedInstance, boolean streaming) {
        JobInstanceVO details = streaming
                ? streamingJobInstanceDao.selectDetailById(submittedInstance.getId())
                : jobInstanceDao.selectDetailById(submittedInstance.getId());
        if (details == null) {
            return submittedInstance;
        }
        copyMissingValues(submittedInstance, details);
        // selectDetailById reads the table ids from the definition row, which may have been
        // edited after this instance was submitted; the submitted values are the ones the
        // Engine actually ran, so they win wherever the submitter recorded them.
        preferSubmittedValues(submittedInstance, details);
        // This is the immutable configuration used to submit this instance. Do not
        // derive field mappings from a subsequently edited job definition.
        details.setRuntimeConfig(submittedInstance.getRuntimeConfig());
        return details;
    }

    private static void copyMissingValues(JobInstanceVO submitted, JobInstanceVO details) {
        if (details.getId() == null) {
            details.setId(submitted.getId());
        }
        if (details.getJobDefinitionId() == null) {
            details.setJobDefinitionId(submitted.getJobDefinitionId());
        }
        if (details.getJobName() == null) {
            details.setJobName(submitted.getJobName());
        }
        if (details.getJobType() == null) {
            details.setJobType(submitted.getJobType());
        }
        if (details.getRunMode() == null) {
            details.setRunMode(submitted.getRunMode());
        }
        if (details.getSourceDatasourceId() == null) {
            details.setSourceDatasourceId(submitted.getSourceDatasourceId());
        }
        if (details.getSinkDatasourceId() == null) {
            details.setSinkDatasourceId(submitted.getSinkDatasourceId());
        }
        if (details.getSourceTable() == null) {
            details.setSourceTable(submitted.getSourceTable());
        }
        if (details.getSinkTable() == null) {
            details.setSinkTable(submitted.getSinkTable());
        }
    }

    private static void preferSubmittedValues(JobInstanceVO submitted, JobInstanceVO details) {
        if (submitted.getSourceDatasourceId() != null) {
            details.setSourceDatasourceId(submitted.getSourceDatasourceId());
        }
        if (submitted.getSinkDatasourceId() != null) {
            details.setSinkDatasourceId(submitted.getSinkDatasourceId());
        }
        if (StringUtils.isNotBlank(submitted.getSourceTable())) {
            details.setSourceTable(submitted.getSourceTable());
        }
        if (StringUtils.isNotBlank(submitted.getSinkTable())) {
            details.setSinkTable(submitted.getSinkTable());
        }
    }

    private OpenLineageSnapshot toStartSnapshot(JobInstanceVO details, String engineJobId, boolean streaming) {
        return toSnapshot(details, engineJobId, streaming, EventType.START, null, null, null);
    }

    private OpenLineageSnapshot toSnapshot(JobInstanceVO details,
                                           String engineJobId,
                                           boolean streaming,
                                           EventType eventType,
                                           String prevStatus,
                                           String errorMessage,
                                           String lastHeartbeat) {
        Config runtimeConfig = StringUtils.isBlank(details.getRuntimeConfig())
                ? null
                : ConfigFactory.parseString(details.getRuntimeConfig());
        OpenLineageJobTopology.Topology topology = OpenLineageJobTopology.parse(runtimeConfig);
        Map<Long, DataSource> dataSourceCache = new HashMap<>();
        List<DataSource> allDataSources = needsDatasourceResolution(topology, details)
                ? dataSourceDao.queryAll()
                : null;
        ResolvedSide inputs = resolveSide(
                topology.sources(),
                details.getSourceTable(),
                details.getSourceDatasourceId(),
                allDataSources,
                dataSourceCache);
        ResolvedSide outputs = resolveSide(
                topology.sinks(),
                details.getSinkTable(),
                details.getSinkDatasourceId(),
                allDataSources,
                dataSourceCache);
        return new OpenLineageSnapshot(
                eventType,
                Instant.now(),
                engineJobId,
                details.getId(),
                details.getJobDefinitionId(),
                details.getJobName(),
                streaming,
                details.getRunMode(),
                details.getJobType(),
                prevStatus,
                errorMessage,
                lastHeartbeat,
                inputs.datasets(),
                outputs.datasets(),
                OpenLineageColumnLineageBuilder.build(
                        inputs.datasets(),
                        outputs.datasets(),
                        FieldMappingExtractor.extractDetailed(runtimeConfig),
                        inputs.aliases(),
                        outputs.aliases()));
    }

    /** One side of the job: its datasets and the transform alias each plugin output owns. */
    private record ResolvedSide(
            List<OpenLineageSnapshot.Dataset> datasets,
            Map<String, List<OpenLineageSnapshot.Dataset>> aliases) {
    }

    private static boolean needsDatasourceResolution(
            OpenLineageJobTopology.Topology topology, JobInstanceVO details) {
        if (topology.sources().size() > 1 || topology.sinks().size() > 1) {
            for (OpenLineageJobTopology.Plugin plugin : topology.sources()) {
                if (plugin.datasourceId() == null) {
                    return true;
                }
            }
            for (OpenLineageJobTopology.Plugin plugin : topology.sinks()) {
                if (plugin.datasourceId() == null) {
                    return true;
                }
            }
        }
        // A single plugin whose instance snapshot carries no datasource can still be resolved by
        // its connection; the other single-plugin cases use the instance snapshot directly.
        return topology.sources().size() == 1
                && topology.sources().get(0).datasourceId() == null
                && details.getSourceDatasourceId() == null
                || topology.sinks().size() == 1
                && topology.sinks().get(0).datasourceId() == null
                && details.getSinkDatasourceId() == null;
    }

    /**
     * The datasets of one side. The submitted configuration is the authority; when it names no
     * concrete table (a query-only connector, a dynamic {@code ${table_name}} pattern or a CDC
     * table-pattern) the table snapshot of the instance is the fallback.
     */
    private ResolvedSide resolveSide(
            List<OpenLineageJobTopology.Plugin> plugins,
            String instanceTable,
            Long instanceDatasourceId,
            List<DataSource> allDataSources,
            Map<Long, DataSource> dataSourceCache) {
        List<OpenLineageSnapshot.Dataset> datasets = new ArrayList<>();
        Map<String, List<OpenLineageSnapshot.Dataset>> aliases = new HashMap<>();
        if (plugins.isEmpty()) {
            for (String table : parseTableList(instanceTable)) {
                addDataset(datasets, instanceDatasourceId, table, dataSourceCache);
            }
            return new ResolvedSide(datasets, aliases);
        }
        boolean singlePlugin = plugins.size() == 1;
        for (OpenLineageJobTopology.Plugin plugin : plugins) {
            Long datasourceId = resolveDatasourceId(
                    plugin, instanceDatasourceId, singlePlugin, allDataSources);
            List<OpenLineageSnapshot.Dataset> pluginDatasets = new ArrayList<>();
            for (String table : plugin.tables()) {
                addDataset(pluginDatasets, datasourceId, table, dataSourceCache);
            }
            for (OpenLineageSnapshot.Dataset dataset : pluginDatasets) {
                addDataset(datasets, dataset.datasourceId(), dataset.name(), dataSourceCache);
            }
            registerAlias(aliases, plugin.outputName(), pluginDatasets);
            registerAlias(aliases, plugin.inputName(), pluginDatasets);
        }
        if (datasets.isEmpty()) {
            for (String table : parseTableList(instanceTable)) {
                addDataset(datasets, instanceDatasourceId, table, dataSourceCache);
            }
        }
        return new ResolvedSide(datasets, aliases);
    }

    private static void registerAlias(
            Map<String, List<OpenLineageSnapshot.Dataset>> aliases,
            String alias,
            List<OpenLineageSnapshot.Dataset> datasets) {
        String name = StringUtils.trimToNull(alias);
        if (name == null || datasets.isEmpty()) {
            return;
        }
        aliases.put(name.toLowerCase(Locale.ROOT), List.copyOf(datasets));
    }

    /**
     * The datasource of one plugin: the configuration wins, the instance column is the fallback of
     * a single plugin job, and a job whose plugin has no instance datasource resolves it by
     * connection.
     */
    private Long resolveDatasourceId(
            OpenLineageJobTopology.Plugin plugin,
            Long instanceDatasourceId,
            boolean singlePlugin,
            List<DataSource> allDataSources) {
        if (plugin.datasourceId() != null) {
            return plugin.datasourceId();
        }
        if (singlePlugin && instanceDatasourceId != null) {
            return instanceDatasourceId;
        }
        return resolveDatasourceByConnection(plugin, allDataSources);
    }

    /**
     * The local datasource whose connection matches the connection of the plugin. A connection
     * that two local datasources share cannot be attributed, so the plugin stays unidentified
     * instead of being attached to a random datasource.
     */
    private Long resolveDatasourceByConnection(
            OpenLineageJobTopology.Plugin plugin, List<DataSource> allDataSources) {
        String url = StringUtils.trimToNull(plugin.url());
        if (url == null) {
            return null;
        }
        List<DataSource> dataSources = allDataSources != null ? allDataSources : dataSourceDao.queryAll();
        if (dataSources == null) {
            return null;
        }
        Long matched = null;
        for (DataSource dataSource : dataSources) {
            if (!sameConnection(url, plugin.username(), dataSource)) {
                continue;
            }
            if (matched != null && !matched.equals(dataSource.getId())) {
                log.warn("OpenLineage bridge cannot attribute the datasource of a job plugin: "
                        + "several local datasources share the connection, url={}", url);
                return null;
            }
            matched = dataSource.getId();
        }
        if (matched == null) {
            log.warn("OpenLineage bridge cannot attribute the datasource of a job plugin: "
                    + "no local datasource matches the connection, url={}", url);
        }
        return matched;
    }

    /** Two connections are the same when their url only differs in case, slash or parameters. */
    private static boolean sameConnection(
            String url, String username, DataSource dataSource) {
        String candidate = connectionUrl(dataSource);
        if (candidate == null || !normalizeUrl(candidate).equals(normalizeUrl(url))) {
            return false;
        }
        String candidateUser = connectionUser(dataSource);
        if (candidateUser == null || username == null) {
            return true;
        }
        return candidateUser.equalsIgnoreCase(username);
    }

    /** The connection url of a local datasource, as stored in its connection parameters. */
    private static String connectionUrl(DataSource dataSource) {
        JsonNode parameters = connectionParameters(dataSource);
        if (parameters == null) {
            return null;
        }
        for (String key : List.of("url", "base-url", "fenodes", "bootstrap.servers")) {
            JsonNode value = parameters.get(key);
            if (value != null && StringUtils.isNotBlank(value.asText())) {
                return StringUtils.trimToNull(value.asText());
            }
        }
        return null;
    }

    /** The connection user of a local datasource, as stored in its connection parameters. */
    private static String connectionUser(DataSource dataSource) {
        JsonNode parameters = connectionParameters(dataSource);
        if (parameters == null) {
            return null;
        }
        JsonNode user = parameters.get("user");
        if (user == null) {
            user = parameters.get("username");
        }
        return user == null ? null : StringUtils.trimToNull(user.asText());
    }

    private static JsonNode connectionParameters(DataSource dataSource) {
        String parameters = dataSource == null ? null : dataSource.getConnectionParams();
        if (StringUtils.isBlank(parameters)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(parameters);
        } catch (JsonProcessingException error) {
            return null;
        }
    }

    private static String normalizeUrl(String url) {
        String value = url.trim().toLowerCase(Locale.ROOT);
        int parameters = value.indexOf('?');
        if (parameters >= 0) {
            value = value.substring(0, parameters);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** Adds one dataset, skipping duplicates and entries without a datasource or a table. */
    private void addDataset(List<OpenLineageSnapshot.Dataset> datasets,
                            Long datasourceId,
                            String table,
                            Map<Long, DataSource> dataSourceCache) {
        if (datasourceId == null || StringUtils.isBlank(table)) {
            return;
        }
        for (OpenLineageSnapshot.Dataset dataset : datasets) {
            if (datasourceId.equals(dataset.datasourceId()) && table.equals(dataset.name())) {
                return;
            }
        }
        DataSource dataSource;
        if (dataSourceCache.containsKey(datasourceId)) {
            dataSource = dataSourceCache.get(datasourceId);
        } else {
            dataSource = dataSourceDao.queryById(datasourceId);
            dataSourceCache.put(datasourceId, dataSource);
        }
        String namespace = dataSource != null && StringUtils.isNotBlank(dataSource.getName())
                ? dataSource.getName()
                : "dbtype:" + datasourceId;
        String dbTypeName = dataSource != null && dataSource.getDbType() != null
                ? dataSource.getDbType().name()
                : null;
        datasets.add(new OpenLineageSnapshot.Dataset(datasourceId, dbTypeName, namespace, table));
    }

    /**
     * Reads the table list the web stores on the job definition: a plain table path, a JSON
     * array (guide multi, script) or a comma separated list (whole sync).
     */
    private static List<String> parseTableList(String raw) {
        List<String> tables = new ArrayList<>();
        String value = StringUtils.trimToNull(raw);
        if (value == null) {
            return tables;
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            try {
                Config parsed = ConfigFactory.parseString("tables = " + value);
                for (ConfigValue element : parsed.getList("tables")) {
                    if (element.valueType() == ConfigValueType.STRING) {
                        addTable(tables, element.unwrapped().toString());
                    } else if (element.valueType() == ConfigValueType.OBJECT) {
                        addTable(tables, tableOf((ConfigObject) element));
                    }
                }
            } catch (RuntimeException error) {
                log.debug("Could not read the JSON table list, value={}", value, error);
            }
            return tables;
        }
        if (value.contains(",")) {
            for (String table : value.split(",")) {
                addTable(tables, table);
            }
            return tables;
        }
        addTable(tables, value);
        return tables;
    }

    private static void addTable(List<String> tables, String table) {
        String value = StringUtils.trimToNull(table);
        if (value == null) {
            return;
        }
        String unquoted = value;
        if (value.length() >= 2
                && (value.charAt(0) == '`' || value.charAt(0) == '"')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            unquoted = StringUtils.trimToNull(value.substring(1, value.length() - 1));
        }
        if (unquoted == null
                || unquoted.contains("${")
                || !unquoted.matches("[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*")) {
            // A script definition stores the raw query as its source table. It is not a table
            // path and must never be published as a dataset name.
            return;
        }
        tables.add(unquoted);
    }

    private static String tableOf(ConfigObject object) {
        for (String field : List.of("table", "table_path", "table_name")) {
            ConfigValue value = object.get(field);
            if (value != null && value.valueType() == ConfigValueType.STRING) {
                return value.unwrapped().toString();
            }
        }
        return null;
    }

    private PublishOutcome publishIfValid(OpenLineageSnapshot snapshot) {
        if (snapshot == null) {
            return PublishOutcome.SKIPPED;
        }
        if (snapshot.inputs().isEmpty() || snapshot.outputs().isEmpty()) {
            // Local lineage is only publishable with both sides identified: the design fixes
            // source/sink datasource id and table path as the minimum viable edge.
            log.info("Skipping OpenLineage {} without source/sink table, instanceId={}, engineJobId={}",
                    snapshot.eventType(), snapshot.instanceId(), snapshot.engineJobId());
            return PublishOutcome.SKIPPED;
        }
        // A terminal is published again by the pulse, so the bridge must not send it a second
        // time on its own: the run would see two terminal events.
        boolean retryOnMissingTopic = snapshot.eventType() == EventType.START
                || snapshot.eventType() == EventType.RUNNING;
        if (!bridge.publish(properties.getKafka().getSourceTopic(),
                OpenLineageEventFactory.toRunEvent(snapshot).toString(),
                retryOnMissingTopic)) {
            log.debug("OpenLineage event was not delivered, instanceId={}, engineJobId={}",
                    snapshot.instanceId(), snapshot.engineJobId());
            return PublishOutcome.FAILED;
        }
        if (snapshot.eventType() == EventType.RUNNING) {
            log.debug("Published OpenLineage RUNNING heartbeat, instanceId={}, engineJobId={}",
                    snapshot.instanceId(), snapshot.engineJobId());
        } else {
            log.info("Published OpenLineage {} event, instanceId={}, engineJobId={}",
                    snapshot.eventType(), snapshot.instanceId(), snapshot.engineJobId());
        }
        return PublishOutcome.PUBLISHED;
    }
}
