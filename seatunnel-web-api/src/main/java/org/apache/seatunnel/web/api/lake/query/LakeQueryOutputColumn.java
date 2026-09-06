package org.apache.seatunnel.web.api.lake.query;

/** Server-generated projection and output alias. */
public record LakeQueryOutputColumn(
        LakeQueryColumnIdentity source,
        String tableAlias,
        String outputAlias) {
}
