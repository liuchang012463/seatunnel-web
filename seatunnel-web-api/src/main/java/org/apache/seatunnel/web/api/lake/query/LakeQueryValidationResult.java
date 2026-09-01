package org.apache.seatunnel.web.api.lake.query;

/** Result shape a later service can map to the existing Result envelope. */
public record LakeQueryValidationResult<T>(
        boolean valid,
        LakeQueryValidationCode code,
        T value) {

    public static <T> LakeQueryValidationResult<T> valid(T value) {
        return new LakeQueryValidationResult<>(true, null, value);
    }

    public static <T> LakeQueryValidationResult<T> invalid(LakeQueryValidationCode code) {
        return new LakeQueryValidationResult<>(false, code, null);
    }

    public boolean isValid() {
        return valid;
    }
}
