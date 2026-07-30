package org.apache.seatunnel.web.api.controller;

import jakarta.servlet.http.Cookie;
import org.apache.seatunnel.web.api.security.Authenticator;
import org.apache.seatunnel.web.spi.bean.dto.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginControllerTest {

    @Mock
    private Authenticator authenticator;

    @InjectMocks
    private LoginController loginController;

    private UserDTO user;

    @BeforeEach
    void setUp() {
        user = new UserDTO();
        user.setUserName("admin");
        user.setUserPassword("password");
        when(authenticator.authenticate(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("sessionId", "test-session"));
    }

    @Test
    void embeddedLoginCreatesCrossSiteSecureCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginController.login(user, true, request, response);

        Cookie cookie = response.getCookie("sessionId");
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("/", cookie.getPath());
        assertEquals("None", cookie.getAttribute("SameSite"));
    }

    @Test
    void standaloneHttpLoginKeepsLaxCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginController.login(user, false, request, response);

        Cookie cookie = response.getCookie("sessionId");
        assertNotNull(cookie);
        assertFalse(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
    }
}
