package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;

/** Transient request used to authenticate and inspect an encrypted package. */
@Data
public class OfflineDatapackInspectDTO {

    @NotNull
    private Long fileResourceId;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private char[] password;
}
