package org.apache.seatunnel.web.api.security;

import org.apache.seatunnel.web.dao.entity.User;

/**
 * Provides the authenticated user for the current request.
 *
 * <p>Resource services use this boundary instead of accepting a user ID from
 * a request body or query parameter. A future SSO adapter only needs to
 * populate the same request context.</p>
 */
public interface CurrentUserProvider {

    User getCurrentUser();

    User requireCurrentUser();

    Integer getCurrentUserId();
}
