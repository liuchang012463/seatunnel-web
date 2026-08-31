package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** User input for an ODS database; the service supplies all ownership fields. */
@Data
public class LakeOdsDatabaseCreateDTO {

    @NotBlank
    private String customName;
}
