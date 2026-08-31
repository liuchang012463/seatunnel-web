package org.apache.seatunnel.web.api.lake;

import lombok.Getter;
import org.apache.seatunnel.web.core.exceptions.ServiceException;

/** Service exception carrying a stable lake error identity. */
@Getter
public class LakeServiceException extends ServiceException {

    private final String lakeErrorCode;

    public LakeServiceException(String lakeErrorCode, String message) {
        super(LakeErrorCode.httpCode(lakeErrorCode), message);
        this.lakeErrorCode = lakeErrorCode;
    }
}
