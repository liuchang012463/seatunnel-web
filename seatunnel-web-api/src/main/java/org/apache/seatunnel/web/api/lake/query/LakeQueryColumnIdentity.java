package org.apache.seatunnel.web.api.lake.query;

/** Validated model identity for one column. */
public record LakeQueryColumnIdentity(LakeQueryTableIdentity table, String column) {
}
