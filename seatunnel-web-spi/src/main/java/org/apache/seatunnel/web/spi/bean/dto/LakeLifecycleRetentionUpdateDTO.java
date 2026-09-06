package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Request to change the frozen lifecycle desired retention for a table. */
@Data
public class LakeLifecycleRetentionUpdateDTO {

    @NotNull
    @Positive
    private Long policyId;

    /** Required only when the requested retention decreases. */
    private String planFingerprint;

    /** Explicit acknowledgement required for a retention reduction. */
    private boolean confirmed;

    /** @deprecated use {@link #planFingerprint}; retained for old clients only. */
    @Deprecated
    @JsonIgnore
    private String confirmationToken;

    public String effectivePlanFingerprint() {
        return planFingerprint == null || planFingerprint.isBlank()
                ? confirmationToken : planFingerprint;
    }
}
