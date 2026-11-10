package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Persisted, non-secret configuration for importing one form into one table. */
@Data
public class OfflineDatapackTaskConfig {

    /** SHA-256 of the encrypted resource inspected before the task was saved. */
    @NotBlank
    @Size(min = 64, max = 64)
    private String packageFingerprint;

    @NotBlank
    @Size(max = 128)
    private String formCode;

    @NotBlank
    @Size(max = 256)
    private String targetTable;

    @Valid
    @NotEmpty
    @Size(max = 512)
    private List<OfflineDatapackFieldMapping> mappings;
}
