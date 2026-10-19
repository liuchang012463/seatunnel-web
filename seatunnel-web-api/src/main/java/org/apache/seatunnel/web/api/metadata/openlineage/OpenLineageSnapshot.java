package org.apache.seatunnel.web.api.metadata.openlineage;

import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageEventFactory.EventType;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Everything the {@link OpenLineageEventFactory} needs to render one OpenLineage {@code RunEvent}.
 *
 * <p>
 * The snapshot is built from the job's local state only (instance identity, engine job id, local
 * datasource name and table path, column field mappings). It intentionally contains no
 * OpenMetadata entity, so the OpenLineage channel never depends on the OM channel.
 * </p>
 */
public record OpenLineageSnapshot(
        EventType eventType,
        Instant eventTime,
        String engineJobId,
        Long instanceId,
        Long jobDefinitionId,
        String jobName,
        boolean streaming,
        String runMode,
        String jobType,
        /** Previous job status; only meaningful for terminal events. */
        String prevStatus,
        /** Error message; only meaningful for terminal events. */
        String errorMessage,
        /** Heartbeat timestamp; only meaningful for periodic RUNNING events of streaming jobs. */
        String lastHeartbeat,
        /** Source dataset; when absent the factory emits an EMPTY inputs array. */
        Dataset input,
        /** Sink dataset; when absent the factory emits an empty outputs array and no column facet. */
        Dataset output,
        /** Mapping from output field name to the source field names it reads; may be empty. */
        Map<String, Set<String>> fieldMappings) {

    /**
     * Local identity of one lineage dataset: namespace is the datasource name, name is the
     * table path exactly as the user typed it.
     */
    public record Dataset(
            Long datasourceId,
            String dbTypeName,
            String namespace,
            String name) {
    }
}
