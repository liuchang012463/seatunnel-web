package org.apache.seatunnel.web.api.lake.ods;

/** Doris identifier, its master-data codes, and the original user-visible name. */
public record OdsDatabaseName(
        String unitCode,
        String systemCode,
        String displayName,
        String databaseName) {
}
