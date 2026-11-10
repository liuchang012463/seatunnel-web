package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** One source form field mapped to a column in an existing target table. */
@Data
public class OfflineDatapackFieldMapping {

    @NotBlank
    @Size(max = 128)
    private String sourceField;

    @NotBlank
    @Size(max = 128)
    private String targetField;
}
