package org.apache.seatunnel.web.api.lake.operation;

/** Runtime failure with a deliberately secret-free message. */
public class LakeOperationException extends RuntimeException {

    public LakeOperationException(String message) {
        super(message);
    }
}
