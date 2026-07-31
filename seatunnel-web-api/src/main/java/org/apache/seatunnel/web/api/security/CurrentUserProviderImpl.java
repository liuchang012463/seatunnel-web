package org.apache.seatunnel.web.api.security;

import org.apache.seatunnel.web.common.constants.Constants;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.User;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Default request-context implementation of {@link CurrentUserProvider}.
 */
@Component
public class CurrentUserProviderImpl implements CurrentUserProvider {

    @Override
    public User getCurrentUser() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }

        Object value = attributes.getAttribute(
                Constants.SESSION_USER,
                RequestAttributes.SCOPE_REQUEST
        );
        return value instanceof User ? (User) value : null;
    }

    @Override
    public User requireCurrentUser() {
        User user = getCurrentUser();
        if (user == null) {
            throw new ServiceException(Status.LOGIN_SESSION_FAILED);
        }
        return user;
    }

    @Override
    public Integer getCurrentUserId() {
        return requireCurrentUser().getId();
    }
}
