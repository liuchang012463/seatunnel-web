package org.apache.seatunnel.web.api.security;

import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.service.SessionService;
import org.apache.seatunnel.web.api.service.UsersService;
import org.apache.seatunnel.web.common.constants.Constants;
import org.apache.seatunnel.web.dao.entity.Session;
import org.apache.seatunnel.web.dao.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the current user before controller and application-service code
 * executes.
 *
 * <p>In {@code DEV_BYPASS}, this filter deliberately ignores all request
 * headers, cookies and request bodies and loads the configured user from the
 * database on every request. The legacy session branch remains isolated for a
 * non-development deployment and can be replaced by a future SSO adapter.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CurrentUserFilter extends OncePerRequestFilter {

    @Value("${security.authentication.mode:DEV_BYPASS}")
    private String authenticationMode = AuthenticationMode.DEV_BYPASS.name();

    @Value("${security.dev-user-id:1}")
    private int devUserId = 1;

    @Resource
    private UsersService usersService;

    @Resource
    private SessionService sessionService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        User currentUser = resolveCurrentUser(request);
        if (isEnabled(currentUser)) {
            request.setAttribute(Constants.SESSION_USER, currentUser);
        }

        filterChain.doFilter(request, response);
    }

    private User resolveCurrentUser(HttpServletRequest request) {
        try {
            if (AuthenticationMode.DEV_BYPASS.name().equalsIgnoreCase(authenticationMode)) {
                return usersService.getById(devUserId);
            }

            Session session = sessionService.getSession(request);
            return session == null ? null : usersService.getById(session.getUserId());
        } catch (Exception e) {
            log.warn("Resolve current user failed, authenticationMode={}", authenticationMode, e);
            return null;
        }
    }

    private boolean isEnabled(User user) {
        return user != null && user.getState() == 1;
    }
}
