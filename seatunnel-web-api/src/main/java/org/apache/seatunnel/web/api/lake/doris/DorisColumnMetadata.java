package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.contract.TargetType;

/** A bounded, non-secret column projection returned by Doris metadata APIs. */
public record DorisColumnMetadata(
        String name,
        int ordinal,
        String type,
        boolean nullable,
        Long length,
        Integer precision,
        Integer scale) {

    public TargetType targetType() {
        return TargetType.parseDorisType(type);
    }
}
