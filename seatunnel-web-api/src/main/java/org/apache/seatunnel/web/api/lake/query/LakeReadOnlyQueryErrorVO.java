package org.apache.seatunnel.web.api.lake.query;

import java.util.Objects;

/** Secret-free error payload that a later controller can expose unchanged. */
public record LakeReadOnlyQueryErrorVO(String code, String message) {

    public LakeReadOnlyQueryErrorVO {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
    }

    public static LakeReadOnlyQueryErrorVO from(LakeQueryExecutionException exception) {
        Objects.requireNonNull(exception, "exception");
        return new LakeReadOnlyQueryErrorVO(exception.errorCode(), exception.getMessage());
    }
}
