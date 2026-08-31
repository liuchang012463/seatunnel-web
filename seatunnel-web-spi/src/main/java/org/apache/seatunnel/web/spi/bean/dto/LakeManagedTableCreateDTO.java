package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Create consumes only an opaque server-issued preview token. */
@Data
public class LakeManagedTableCreateDTO {

    @NotBlank
    private String previewToken;
}
