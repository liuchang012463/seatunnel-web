package org.apache.seatunnel.web.api.metadata.openlineage;

import org.apache.seatunnel.web.api.metadata.openlineage.OpenLineageEventFactory.EventType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything the {@link OpenLineageEventFactory} needs to render one OpenLineage {@code RunEvent}.
 *
 * <p>
 * The snapshot is built from the job's local state only (instance identity, engine job id, local
 * datasource names and table paths, column field mappings). It intentionally contains no
 * OpenMetadata entity, so lineage publication never depends on OpenMetadata.
 * </p>
 *
 * <p>
 * Inputs and outputs are collections: a job may read many tables and write many tables, which is
 * what the OpenLineage spec models as the {@code inputs} and {@code outputs} arrays. The four
 * table-level lineage cardinalities (1:1, N:1, 1:N, N:M) are all expressed by the size of those
 * two lists.
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
        /** Source datasets; when empty the factory emits an empty inputs array. */
        List<Dataset> inputs,
        /** Sink datasets; when empty the factory emits an empty outputs array and no column facet. */
        List<Dataset> outputs,
        /** Column lineage per output dataset; empty when only table level lineage is known. */
        List<OutputLineage> lineages) {

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

    /**
     * One source column, bound to the dataset it belongs to. The binding is what makes the
     * column lineage unambiguous: without it a column of two source tables cannot be told apart.
     */
    public record SourceColumn(Dataset dataset, String column) {
    }

    /**
     * The column lineage of one output dataset: the output column and the source columns it
     * reads. A column with several sources is a many-to-one mapping.
     */
    public record OutputLineage(Dataset output, Map<String, List<SourceColumn>> columns) {
    }
}
