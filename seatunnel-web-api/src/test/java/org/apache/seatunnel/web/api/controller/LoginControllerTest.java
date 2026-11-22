package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.security.Authenticator;
import org.apache.seatunnel.web.common.constants.Constants;
import org.apache.seatunnel.web.spi.bean.dto.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

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
    }

    @Test
    void devBypassDoesNotAuthenticateOrCreateCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginController.login(user, true, request, response);

        assertNull(response.getCookie("sessionId"));
        verifyNoInteractions(authenticator);
    }

    @Test
    void passwordLoginDefaultsToSecureCookie() {
        ReflectionTestUtils.setField(loginController, "authenticationMode", "PASSWORD");
        when(authenticator.authenticate(anyString(), anyString(), anyString()))
                .thenReturn(Map.of(Constants.SESSION_ID, "password-session"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        loginController.login(user, false, new MockHttpServletRequest(), response);

        assertTrue(response.getCookie(Constants.SESSION_ID).getSecure());
        assertEquals("Lax", response.getCookie(Constants.SESSION_ID).getAttribute("SameSite"));
    }

    @Test
    void localHttpCanDisableSecureButSecureRequestsAndEmbeddedCookiesStaySecure() {
        ReflectionTestUtils.setField(loginController, "authenticationMode", "PASSWORD");
        ReflectionTestUtils.setField(loginController, "secureCookies", false);
        when(authenticator.authenticate(anyString(), anyString(), anyString()))
                .thenReturn(Map.of(Constants.SESSION_ID, "password-session"));

        MockHttpServletResponse localResponse = new MockHttpServletResponse();
        loginController.login(user, false, new MockHttpServletRequest(), localResponse);
        assertFalse(localResponse.getCookie(Constants.SESSION_ID).getSecure());

        MockHttpServletRequest secureRequest = new MockHttpServletRequest();
        secureRequest.setSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        loginController.login(user, false, secureRequest, secureResponse);
        assertTrue(secureResponse.getCookie(Constants.SESSION_ID).getSecure());

        MockHttpServletResponse embeddedResponse = new MockHttpServletResponse();
        loginController.login(user, true, new MockHttpServletRequest(), embeddedResponse);
        assertTrue(embeddedResponse.getCookie(Constants.SESSION_ID).getSecure());
        assertEquals("None", embeddedResponse.getCookie(Constants.SESSION_ID).getAttribute("SameSite"));
    }
}
