package org.apache.seatunnel.web.api.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.apache.seatunnel.web.api.service.SessionService;
import org.apache.seatunnel.web.api.service.UsersService;
import org.apache.seatunnel.web.common.constants.Constants;
import org.apache.seatunnel.web.dao.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserFilterTest {

    @Mock
    private UsersService usersService;

    @Mock
    private SessionService sessionService;

    private CurrentUserFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CurrentUserFilter();
        ReflectionTestUtils.setField(filter, "usersService", usersService);
        ReflectionTestUtils.setField(filter, "sessionService", sessionService);
        ReflectionTestUtils.setField(filter, "authenticationMode", AuthenticationMode.DEV_BYPASS.name());
        ReflectionTestUtils.setField(filter, "devUserId", 1);
    }

    @Test
    void devBypassLoadsAdminAndIgnoresSessionCookieAndSpoofedHeader()
            throws ServletException, IOException {
        User admin = new User();
        admin.setId(1);
        admin.setUserName("admin");
        admin.setState(1);
        when(usersService.getById(1)).thenReturn(admin);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(Constants.SESSION_ID, "spoofed-session");
        request.setCookies(new Cookie(Constants.SESSION_ID, "spoofed-cookie"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertSame(admin, request.getAttribute(Constants.SESSION_USER));
        verify(usersService).getById(1);
        verify(sessionService, never()).getSession(any());
    }
}
