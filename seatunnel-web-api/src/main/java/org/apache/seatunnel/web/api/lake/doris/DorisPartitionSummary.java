package org.apache.seatunnel.web.api.lake.doris;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable observation of the temporal shape of one Doris table. */
public record DorisPartitionSummary(
        int total,
        int historical,
        int current,
        int future,
        int unknown,
        List<String> partitionNames,
        Instant observedAt,
        List<String> historicalPartitionNames,
        List<String> currentPartitionNames,
        List<String> futurePartitionNames,
        List<String> unknownPartitionNames) {

    public DorisPartitionSummary {
        if (total < 0 || historical < 0 || current < 0 || future < 0 || unknown < 0
                || historical + current + future + unknown != total) {
            throw new IllegalArgumentException("Invalid Doris partition summary counts");
        }
        partitionNames = partitionNames == null ? List.of() : List.copyOf(partitionNames);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        historicalPartitionNames = historicalPartitionNames == null
                ? List.of() : List.copyOf(historicalPartitionNames);
        currentPartitionNames = currentPartitionNames == null
                ? List.of() : List.copyOf(currentPartitionNames);
        futurePartitionNames = futurePartitionNames == null
                ? List.of() : List.copyOf(futurePartitionNames);
        unknownPartitionNames = unknownPartitionNames == null
                ? List.of() : List.copyOf(unknownPartitionNames);
    }

    /** Compatibility constructor for callers that only need the aggregate list. */
    public DorisPartitionSummary(int total, int historical, int current, int future, int unknown,
                                 List<String> partitionNames, Instant observedAt) {
        this(total, historical, current, future, unknown, partitionNames, observedAt,
                List.of(), List.of(), List.of(), List.of());
    }

    public int totalCount() {
        return total;
    }

    public int historicalCount() {
        return historical;
    }

    public int currentCount() {
        return current;
    }

    public int futureCount() {
        return future;
    }

    public int unknownCount() {
        return unknown;
    }

    public List<String> historicalNames() {
        return historicalPartitionNames;
    }

    public List<String> currentNames() {
        return currentPartitionNames;
    }

    public List<String> futureNames() {
        return futurePartitionNames;
    }

    public List<String> unknownNames() {
        return unknownPartitionNames;
    }
}
