package org.apache.seatunnel.web.api.metadata.openlineage;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.alarm.event.JobStatusChangedEvent;
import org.apache.seatunnel.web.api.metadata.FieldMappingExtractor;
import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageEventFactory.EventType;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes SeaTunnel job lineage to Kafka as OpenLineage {@code RunEvent} JSON.
 *
 * <p>
 * This is a channel fully parallel to {@code OpenMetadataJobLineageService}: it fetches the job
 * instances, runtime configs and datasource names itself, uses the local names as dataset
 * identity (the asset project resolves them to OpenMetadata entities), and never reads or waits
 * for the OpenMetadata channel. Whole-run life cycle per the bridge design v1: one START at
 * submission acceptance, periodic RUNNING heartbeats for streaming jobs, exactly one terminal
 * COMPLETE / FAIL / ABORT per run. The runId is derived deterministically from the engine job id,
 * so START and the terminal event share one runId even across a web restart.
 * </p>
 *
 * <p>
 * Terminal handling per running model (both confirmed against the code):
 * Batch jobs: one {@link JobStatusChangedEvent} per final status is published by the batch
 * result handler, consumed here. Streaming jobs do not publish that event (its alarm lookup only
 * knows the batch table), so the pulse below detects a stream terminal when a previously tracked
 * instance disappears from the running-like set, and maps the status it actually reached.
 * </p>
 */
@Service
@ConditionalOnProperty(prefix = "lineage-bridge.kafka", name = "enabled", havingValue = "true")
@Slf4j
public class OpenLineageJobLineageService {

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
     * START of one run, called next to the OpenMetadata lineage write from the submitters. Async
     * on the independent lineage track: the Engine has accepted the job, so this must never
     * extend the submission response.
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
     * (FINISHED / FAILED / CANCELED); every other status (SUBMISSION_UNKNOWN, transitions, ...)
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
            if (event.getNewStatus() == JobStatus.SUBMISSION_UNKNOWN) {
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
            String prevStatus = event.getOldStatus() == null ? null : event.getOldStatus().name();
            publishIfValid(toSnapshot(details, engineJobId, false, eventType,
                    prevStatus, event.getErrorMessage(), null));
        } catch (Exception error) {
            log.warn("Could not publish OpenLineage terminal event, instanceId={}, status={}",
                    event.getJobInstanceId(), event.getNewStatus(), error);
        }
    }

    /**
     * Periodic pulse for streaming jobs: RUNNING heartbeats with the same lineage as START, and
     * terminal detection via vanishing from the running set. Runs on the shared scheduler thread,
     * so it should stay cheap at default 60s cadence.
     */
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

            List<Long> vanished = new ArrayList<>(monitoredStreamingStatuses.keySet());
            vanished.removeAll(present);
            for (Long instanceId : vanished) {
                publishStreamingTerminal(instanceId, monitoredStreamingStatuses.get(instanceId));
            }
            monitoredStreamingStatuses.keySet().retainAll(present);
            for (JobInstanceVO listed : running) {
                if (listed != null && listed.getId() != null) {
                    monitoredStreamingStatuses.put(listed.getId(), listed.getJobStatus());
                }
            }
        } catch (Exception error) {
            log.warn("OpenLineage streaming pulse failed", error);
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

    private void publishStreamingTerminal(Long instanceId, String lastSeenStatus) {
        try {
            JobInstanceVO details = streamingJobInstanceDao.selectDetailById(instanceId);
            if (details == null) {
                // The row is gone (definition deleted); there is no run left to report.
                return;
            }
            EventType eventType = streamingTerminalType(details.getJobStatus());
            if (eventType == null) {
                log.debug("OpenLineage bridge drops vanished streaming instance with status={}",
                        details.getJobStatus());
                return;
            }
            if (StringUtils.isBlank(details.getEngineJobId())) {
                return;
            }
            publishIfValid(toSnapshot(details, details.getEngineJobId(), true, eventType,
                    lastSeenStatus, details.getErrorMessage(), null));
        } catch (Exception error) {
            log.warn("Could not publish OpenLineage streaming terminal, instanceId={}", instanceId, error);
        }
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
                // SUBMISSION_UNKNOWN / UNKNOWABLE / transitions: no event, log only.
                return null;
        }
    }

    /**
     * Streaming terminal mapping. The contract fixes the streaming vocabulary to FAIL / ABORT —
     * a streaming run never emits COMPLETE, so even a FINISHED engine result is reported as ABORT
     * with the reached status kept in prevStatus.
     */
    private static EventType streamingTerminalType(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase()) {
            case "FAILED" -> EventType.FAIL;
            case "FINISHED", "CANCELED", "CANCELLED", "STOPPED", "SAVEPOINT_DONE" -> EventType.ABORT;
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
                resolveDataset(details.getSourceDatasourceId(), details.getSourceTable()),
                resolveDataset(details.getSinkDatasourceId(), details.getSinkTable()),
                FieldMappingExtractor.extract(runtimeConfig));
    }

    /** Datasets are published under the local identity only; asset performs the name → OM mapping. */
    private OpenLineageSnapshot.Dataset resolveDataset(Long datasourceId, String tablePath) {
        if (datasourceId == null || StringUtils.isBlank(tablePath)) {
            return null;
        }
        DataSource dataSource = dataSourceDao.queryById(datasourceId);
        String namespace = dataSource != null && StringUtils.isNotBlank(dataSource.getName())
                ? dataSource.getName()
                : "dbtype:" + datasourceId;
        String dbTypeName = dataSource != null && dataSource.getDbType() != null
                ? dataSource.getDbType().name()
                : null;
        return new OpenLineageSnapshot.Dataset(datasourceId, dbTypeName, namespace, tablePath);
    }

    private void publishIfValid(OpenLineageSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.input() == null || snapshot.output() == null) {
            // Local lineage is only publishable with both sides identified: the design fixes
            // source/sink datasource id and table path as the minimum viable edge.
            log.info("Skipping OpenLineage {} without source/sink table, instanceId={}, engineJobId={}",
                    snapshot.eventType(), snapshot.instanceId(), snapshot.engineJobId());
            return;
        }
        bridge.publish(properties.getKafka().getSourceTopic(),
                OpenLineageEventFactory.toRunEvent(snapshot).toString());
        if (snapshot.eventType() == EventType.RUNNING) {
            log.debug("Published OpenLineage RUNNING heartbeat, instanceId={}, engineJobId={}",
                    snapshot.instanceId(), snapshot.engineJobId());
        } else {
            log.info("Published OpenLineage {} event, instanceId={}, engineJobId={}",
                    snapshot.eventType(), snapshot.instanceId(), snapshot.engineJobId());
        }
    }
}
