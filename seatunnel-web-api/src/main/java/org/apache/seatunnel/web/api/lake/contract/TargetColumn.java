package org.apache.seatunnel.web.api.lake.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A source-to-target column entry in TargetContract v2. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "sourceName", "sourceOrdinal", "targetName", "targetType", "nullable", "key", "physicalOrdinal"
})
public class TargetColumn {

    private String sourceName;
    private Integer sourceOrdinal;
    private String targetName;
    private TargetType targetType;
    private Boolean nullable;
    private Boolean key;
    private Integer physicalOrdinal;

    public TargetColumn(String sourceName, int sourceOrdinal, String targetName,
                        TargetType targetType, boolean nullable, boolean key, int physicalOrdinal) {
        this.sourceName = sourceName;
        this.sourceOrdinal = sourceOrdinal;
        this.targetName = targetName;
        this.targetType = targetType;
        this.nullable = nullable;
        this.key = key;
        this.physicalOrdinal = physicalOrdinal;
    }
}
