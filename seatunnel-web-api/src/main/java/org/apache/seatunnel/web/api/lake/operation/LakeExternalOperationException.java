package org.apache.seatunnel.web.api.lake.operation;

/** Secret-free classification for an external operation failure. */
public class LakeExternalOperationException extends RuntimeException {

    private final String errorCode;

    public LakeExternalOperationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
