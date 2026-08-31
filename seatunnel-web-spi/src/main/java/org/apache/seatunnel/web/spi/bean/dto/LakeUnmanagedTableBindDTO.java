package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Explicit request to associate an existing Doris table with an OM table. */
@Data
public class LakeUnmanagedTableBindDTO {

    @NotNull
    private Long odsDatabaseBindingId;

    @NotBlank
    private String targetTableName;

    @NotNull
    private Long sourceDataSourceId;

    @NotBlank
    private String omEntityId;
}
