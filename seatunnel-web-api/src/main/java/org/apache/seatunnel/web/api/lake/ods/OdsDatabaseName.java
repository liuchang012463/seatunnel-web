package org.apache.seatunnel.web.api.lake.ods;

/** Canonical ODS database name and the master-data codes used to derive it. */
public record OdsDatabaseName(
        String unitCode,
        String systemCode,
        String customName,
        String databaseName) {
}
