package org.apache.seatunnel.web.api.metadata;

import lombok.Getter;

/** Deliberately sanitized exception used at the OM boundary. */
@Getter
public class MetadataIntegrationException extends RuntimeException {

    private final MetadataErrorCode errorCode;
    private final Integer httpStatusCode;

    public MetadataIntegrationException(MetadataErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public MetadataIntegrationException(MetadataErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, null);
    }

    public MetadataIntegrationException(
            MetadataErrorCode errorCode, String message, Throwable cause, Integer httpStatusCode) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatusCode = httpStatusCode;
    }
}
