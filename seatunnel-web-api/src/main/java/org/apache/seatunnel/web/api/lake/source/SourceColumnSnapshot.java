package org.apache.seatunnel.web.api.lake.source;

/** Structural source-column projection retained by a lake source reference. */
public record SourceColumnSnapshot(
        String name,
        Integer ordinal,
        String dataType,
        String dataTypeDisplay,
        Long dataLength,
        Long precision,
        Long scale,
        String constraint,
        Boolean nullable) {
}
