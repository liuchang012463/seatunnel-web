package org.apache.seatunnel.web.api.lake.source;

import java.util.List;

/** Current OM identity plus the immutable structural snapshot used for drift. */
public record SourceObjectSnapshot(
        String omEntityId,
        String omFqn,
        List<SourceColumnSnapshot> columns,
        List<SourceConstraintSnapshot> constraints,
        String sourceSchemaHash,
        String snapshotJson) {

    public SourceObjectSnapshot {
        columns = columns == null ? List.of() : List.copyOf(columns);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }
}
