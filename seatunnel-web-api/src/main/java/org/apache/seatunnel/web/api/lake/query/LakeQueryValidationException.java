package org.apache.seatunnel.web.api.lake.query;

/** Exception carrying a stable, service-safe query validation identity. */
public final class LakeQueryValidationException extends IllegalArgumentException {

    private final LakeQueryValidationCode code;

    public LakeQueryValidationException(LakeQueryValidationCode code) {
        super(code.code());
        this.code = code;
    }

    public LakeQueryValidationCode code() {
        return code;
    }

    public LakeQueryValidationCode getCode() {
        return code;
    }
}
