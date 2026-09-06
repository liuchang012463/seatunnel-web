package org.apache.seatunnel.web.api.lake.query;

import java.util.List;
import java.util.Optional;

/** Immutable metadata allowlist supplied by the caller of the generator. */
public record LakeQueryColumnAllowlist(List<LakeQueryColumnMetadata> columns) {

    public LakeQueryColumnAllowlist {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public Optional<LakeQueryColumnMetadata> find(String name) {
        return columns.stream().filter(column -> column != null
                && name.equals(column.name())).findFirst();
    }
}
