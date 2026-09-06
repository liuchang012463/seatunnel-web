package org.apache.seatunnel.web.api.lake.job;

import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;

/**
 * Server-derived identity and snapshots for a structured job that targets the
 * configured lake Doris data source.
 */
public record LakeJobDescriptor(
        Long odsDatabaseBindingId,
        Long lakeDataSourceId,
        Long sourceDataSourceId,
        Long sinkDataSourceId,
        LakeRelationScope relationScope,
        Long tableMappingId,
        LakeJobRuntimeType jobRuntimeType,
        String sourceEndpointSnapshot,
        String sinkEndpointSnapshot,
        String schemaSaveModeSnapshot) {
}
