package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** Request to inspect an encrypted datapack for CSV members. */
@Getter
@Setter
public class OfflineDatapackCsvInspectDTO {

    @NotNull
    private Long fileResourceId;

    @NotEmpty
    private char[] password;
}
