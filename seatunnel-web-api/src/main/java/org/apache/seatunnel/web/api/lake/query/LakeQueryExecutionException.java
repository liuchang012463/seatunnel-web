package org.apache.seatunnel.web.api.lake.query;

import java.util.Objects;

/** Execution exception whose message never contains SQL, rows, or JDBC details. */
public final class LakeQueryExecutionException extends RuntimeException {

    private final String errorCode;

    public LakeQueryExecutionException(String errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode"));
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public String code() {
        return errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getCode() {
        return errorCode;
    }
}
