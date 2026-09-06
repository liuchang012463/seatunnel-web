package org.apache.seatunnel.web.api.lake.source;

import java.util.List;

/** Structural table-constraint projection retained by a lake source reference. */
public record SourceConstraintSnapshot(
        String constraintType,
        List<String> columns,
        List<String> referredColumns,
        String relationshipType) {
}
