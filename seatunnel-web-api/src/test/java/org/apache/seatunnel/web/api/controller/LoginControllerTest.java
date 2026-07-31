package org.apache.seatunnel.web.api.controller;

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

import static org.junit.jupiter.api.Assertions.assertNull;
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
}
