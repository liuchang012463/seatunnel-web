package org.apache.seatunnel.web.api.metadata;

import lombok.Getter;

/** Deliberately sanitized exception used at the OM boundary. */
@Getter
public class MetadataIntegrationException extends RuntimeException {

    private final MetadataErrorCode errorCode;

    public MetadataIntegrationException(MetadataErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MetadataIntegrationException(MetadataErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
