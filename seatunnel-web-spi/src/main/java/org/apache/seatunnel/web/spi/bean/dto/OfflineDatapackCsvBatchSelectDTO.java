package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Extract a set of CSV members from the same inspected package. */
@Getter
@Setter
public class OfflineDatapackCsvBatchSelectDTO extends OfflineDatapackCsvInspectDTO {

    @NotBlank
    @Pattern(regexp = "[a-fA-F0-9]{64}")
    private String packageFingerprint;

    @NotEmpty
    @Size(max = 10_000)
    private List<@NotBlank @Size(max = 1024) String> fileNames;
}
