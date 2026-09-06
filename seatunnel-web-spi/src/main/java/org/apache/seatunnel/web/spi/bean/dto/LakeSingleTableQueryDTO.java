package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Request contract for a bounded projection from one catalog table. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LakeSingleTableQueryDTO(
        LakeQueryTableIdentityDTO table,
        List<LakeQueryColumnIdentityDTO> selectedColumns,
        Integer limit,
        Boolean explain,
        Long catalogBindingId,
        String queryId) {

    /** Keeps the original structured request source-compatible. */
    public LakeSingleTableQueryDTO(
            LakeQueryTableIdentityDTO table,
            List<LakeQueryColumnIdentityDTO> selectedColumns,
            Integer limit,
            Boolean explain) {
        this(table, selectedColumns, limit, explain, null, null);
    }

    public LakeSingleTableQueryDTO(
            LakeQueryTableIdentityDTO table,
            List<LakeQueryColumnIdentityDTO> selectedColumns,
            Integer limit,
            Boolean explain,
            Long catalogBindingId) {
        this(table, selectedColumns, limit, explain, catalogBindingId, null);
    }

    public LakeSingleTableQueryDTO {
        selectedColumns = selectedColumns == null ? List.of() : List.copyOf(selectedColumns);
    }
}
