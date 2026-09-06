package org.apache.seatunnel.web.api.lake.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Distribution part of TargetContract v2. */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "columns", "buckets"})
public class TargetDistribution {

    public static final String RANDOM = "RANDOM";
    public static final String HASH = "HASH";
    public static final String AUTO = "AUTO";

    private String type = RANDOM;
    private List<String> columns = new ArrayList<>();
    private String buckets = AUTO;

    public TargetDistribution(String type, List<String> columns, String buckets) {
        this.type = type;
        this.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
        this.buckets = buckets;
    }

    public static TargetDistribution random() {
        return new TargetDistribution(RANDOM, List.of(), AUTO);
    }

    public static TargetDistribution hash(List<String> columns) {
        return new TargetDistribution(HASH, columns, AUTO);
    }
}
