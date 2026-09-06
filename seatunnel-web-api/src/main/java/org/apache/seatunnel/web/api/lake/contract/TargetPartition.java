package org.apache.seatunnel.web.api.lake.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Auto-range partition part of TargetContract v2. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"enabled", "column", "granularity"})
public class TargetPartition {

    private Boolean enabled = false;
    private String column;
    private String granularity;

    public TargetPartition(boolean enabled, String column, String granularity) {
        this.enabled = enabled;
        this.column = column;
        this.granularity = granularity;
    }

    public static TargetPartition disabled() {
        return new TargetPartition(false, null, null);
    }

    public static TargetPartition autoRange(String column, String granularity) {
        return new TargetPartition(true, column, granularity);
    }
}
