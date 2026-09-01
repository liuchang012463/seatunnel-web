package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Request contract for an equality join between two catalogs. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LakeJoinQueryDTO(
        LakeQueryTableIdentityDTO leftTable,
        LakeQueryTableIdentityDTO rightTable,
        List<LakeQueryColumnIdentityDTO> leftColumns,
        List<LakeQueryColumnIdentityDTO> rightColumns,
        LakeQueryColumnIdentityDTO leftJoinColumn,
        LakeQueryColumnIdentityDTO rightJoinColumn,
        Integer limit,
        Boolean explain) {

    public LakeJoinQueryDTO {
        leftColumns = leftColumns == null ? List.of() : List.copyOf(leftColumns);
        rightColumns = rightColumns == null ? List.of() : List.copyOf(rightColumns);
    }
}
