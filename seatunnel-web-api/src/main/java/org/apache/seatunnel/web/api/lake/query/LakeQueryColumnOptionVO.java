package org.apache.seatunnel.web.api.lake.query;

/** Safe column option for the structured query picker. */
public record LakeQueryColumnOptionVO(
        String name,
        String type,
        boolean nullable,
        boolean selectable,
        String reason) {
}
