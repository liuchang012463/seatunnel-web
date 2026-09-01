package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Request contract for a bounded projection from one catalog table. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LakeSingleTableQueryDTO(
        LakeQueryTableIdentityDTO table,
        List<LakeQueryColumnIdentityDTO> selectedColumns,
        Integer limit,
        Boolean explain) {

    public LakeSingleTableQueryDTO {
        selectedColumns = selectedColumns == null ? List.of() : List.copyOf(selectedColumns);
    }
}
