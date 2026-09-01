package org.apache.seatunnel.web.api.lake.doris;

/**
 * The stable subset of Doris SHOW PARTITIONS output needed by lifecycle
 * observation.  Boundary text is retained verbatim (apart from outer
 * whitespace); callers must treat a missing boundary as unknown.
 */
public record DorisPartitionMetadata(
        String partitionName,
        String state,
        String partitionKey,
        String range,
        String lowerBound,
        String upperBound) {

    public DorisPartitionMetadata {
        partitionName = trimToNull(partitionName);
        state = trimToNull(state);
        partitionKey = trimToNull(partitionKey);
        range = trimToNull(range);
        lowerBound = trimToNull(lowerBound);
        upperBound = trimToNull(upperBound);
    }

    public String rangeText() {
        return range;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
