package org.apache.seatunnel.web.api.lake.query;

/** Validated model identity for one catalog table. */
public record LakeQueryTableIdentity(String catalog, String database, String table) {
}
