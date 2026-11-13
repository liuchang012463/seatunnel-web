package org.apache.seatunnel.web.spi.bean.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Request to extract one previously inspected CSV member from an owned datapack. */
@Getter
@Setter
public class OfflineDatapackCsvSelectDTO {

    @NotNull
    private Long fileResourceId;

    @NotEmpty
    private char[] password;

    @NotBlank
    @Size(min = 64, max = 64)
    @Pattern(regexp = "[a-fA-F0-9]{64}")
    private String packageFingerprint;

    @NotBlank
    @Size(max = 1024)
    private String fileName;
}
