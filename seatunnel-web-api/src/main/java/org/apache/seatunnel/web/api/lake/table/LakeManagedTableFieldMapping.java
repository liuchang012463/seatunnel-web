package org.apache.seatunnel.web.api.lake.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The structured mapping shape reused by existing single-table task flows. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"sourceField", "targetField", "targetType"})
public class LakeManagedTableFieldMapping {

    private String sourceField;

    private String targetField;

    private String targetType;
}
