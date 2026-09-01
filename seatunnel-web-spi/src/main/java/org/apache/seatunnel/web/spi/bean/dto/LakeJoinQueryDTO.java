package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Locale;

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
        Boolean explain,
        String joinType) {

    /** Keeps clients compiled against the original INNER JOIN-only contract source-compatible. */
    public LakeJoinQueryDTO(
            LakeQueryTableIdentityDTO leftTable,
            LakeQueryTableIdentityDTO rightTable,
            List<LakeQueryColumnIdentityDTO> leftColumns,
            List<LakeQueryColumnIdentityDTO> rightColumns,
            LakeQueryColumnIdentityDTO leftJoinColumn,
            LakeQueryColumnIdentityDTO rightJoinColumn,
            Integer limit,
            Boolean explain) {
        this(leftTable, rightTable, leftColumns, rightColumns, leftJoinColumn,
                rightJoinColumn, limit, explain, "INNER");
    }

    public LakeJoinQueryDTO {
        leftColumns = leftColumns == null ? List.of() : List.copyOf(leftColumns);
        rightColumns = rightColumns == null ? List.of() : List.copyOf(rightColumns);
        joinType = joinType == null || joinType.isBlank()
                ? "INNER" : joinType.trim().toUpperCase(Locale.ROOT);
    }
}
