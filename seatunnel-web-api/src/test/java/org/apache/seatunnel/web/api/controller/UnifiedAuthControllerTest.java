package org.apache.seatunnel.web.api.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.SessionService;
import org.apache.seatunnel.web.api.service.impl.UnifiedAuthServiceImpl;
import org.apache.seatunnel.web.common.constants.Constants;
import org.apache.seatunnel.web.common.enums.UserType;
import org.apache.seatunnel.web.dao.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class UnifiedAuthControllerTest {

    private static final String FRONTEND_URL = "https://web.example.com/";

    @Test
    void oidcCallbackCreatesNormalSessionAndRedirectsWithoutAuthorizationData() {
        User user = new User();
        UnifiedAuthServiceImpl.OidcCallbackResult result =
                new UnifiedAuthServiceImpl.OidcCallbackResult(user, false);
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.completeLogin("authorization-code", "state-token", "browser-binding")).thenReturn(result);
        when(service.callbackRedirect(result)).thenReturn(FRONTEND_URL);
        when(service.callbackFailureRedirect()).thenReturn(FRONTEND_URL + "?oidc=failed");
        SessionService sessions = mock(SessionService.class);
        when(sessions.createSession(user, "127.0.0.1")).thenReturn("normal-session-id");
        HttpServletRequest request = secureRequest(
                new Cookie("ST_OIDC_BROWSER_BINDING", "browser-binding"));
        HttpServletResponse response = mock(HttpServletResponse.class);
        UnifiedAuthController controller = controller(service, sessions);

        var redirect = controller.callback("authorization-code", "state-token", null, request, response);

        assertEquals(HttpStatus.FOUND, redirect.getStatusCode());
        assertEquals(FRONTEND_URL, redirect.getHeaders().getLocation().toString());
        ArgumentCaptor<String> cookies = ArgumentCaptor.forClass(String.class);
        verify(response, times(2)).addHeader(eq(HttpHeaders.SET_COOKIE), cookies.capture());
        List<String> setCookies = cookies.getAllValues();
        assertTrue(setCookies.stream().anyMatch(cookie -> cookie.contains(
                Constants.SESSION_ID + "=normal-session-id;") && cookie.contains("HttpOnly")
                && cookie.contains("Secure") && cookie.contains("SameSite=Lax")));
        assertTrue(setCookies.stream().anyMatch(cookie -> cookie.startsWith("ST_OIDC_BROWSER_BINDING=;")
                && cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("Max-Age=0")));
        verify(service).completeLogin("authorization-code", "state-token", "browser-binding");
    }

    @Test
    void oidcStartSetsHttpOnlySameSiteBrowserBindingCookie() {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.beginLogin()).thenReturn(new UnifiedAuthServiceImpl.OidcLoginStart(
                "https://identity.example.com/authorize", "browser-binding", 600));
        UnifiedAuthController controller = controller(service, mock(SessionService.class));

        var redirect = controller.startLogin(secureRequest());

        assertEquals(HttpStatus.FOUND, redirect.getStatusCode());
        assertEquals("https://identity.example.com/authorize", redirect.getHeaders().getLocation().toString());
        String bindingCookie = redirect.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(bindingCookie.startsWith("ST_OIDC_BROWSER_BINDING=browser-binding;"));
        assertTrue(bindingCookie.contains("HttpOnly"));
        assertTrue(bindingCookie.contains("Secure"));
        assertTrue(bindingCookie.contains("SameSite=Lax"));
        assertTrue(bindingCookie.contains("Max-Age=600"));
    }

    @Test
    void externalTicketCreatesTheSameNormalSessionAndRedirectsCleanly() {
        User user = new User();
        UnifiedAuthServiceImpl.OidcCallbackResult result =
                new UnifiedAuthServiceImpl.OidcCallbackResult(user, false);
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.completeExternalTicket("single-use-ticket")).thenReturn(result);
        when(service.callbackRedirect(result)).thenReturn(FRONTEND_URL);
        when(service.callbackFailureRedirect()).thenReturn(FRONTEND_URL + "?oidc=failed");
        SessionService sessions = mock(SessionService.class);
        when(sessions.createSession(user, "127.0.0.1")).thenReturn("normal-session-id");
        HttpServletRequest request = secureRequest();
        HttpServletResponse response = mock(HttpServletResponse.class);
        UnifiedAuthController controller = controller(service, sessions);

        var redirect = controller.enterExternalTicketSession("single-use-ticket", request, response);

        assertEquals(HttpStatus.FOUND, redirect.getStatusCode());
        assertEquals(FRONTEND_URL, redirect.getHeaders().getLocation().toString());
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE),
                contains(Constants.SESSION_ID + "=normal-session-id;"));
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), contains("HttpOnly"));
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), contains("Secure"));
    }

    @Test
    void oidcCallbackRouteBindsNamedQueryParameters() throws Exception {
        User user = new User();
        UnifiedAuthServiceImpl.OidcCallbackResult result =
                new UnifiedAuthServiceImpl.OidcCallbackResult(user, false);
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.completeLogin("authorization-code", "state-token", "browser-binding")).thenReturn(result);
        when(service.callbackRedirect(result)).thenReturn(FRONTEND_URL);
        SessionService sessions = mock(SessionService.class);
        when(sessions.createSession(user, "127.0.0.1")).thenReturn("normal-session-id");
        UnifiedAuthController controller = controller(service, sessions);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        var response = mvc.perform(get("/api/v1/unified-auth/oidc/callback/seatunnel")
                        .param("code", "authorization-code")
                        .param("state", "state-token")
                        .cookie(new Cookie("ST_OIDC_BROWSER_BINDING", "browser-binding"))
                        .secure(true))
                .andReturn()
                .getResponse();

        assertEquals(HttpStatus.FOUND.value(), response.getStatus());
        assertEquals(FRONTEND_URL, response.getHeader(HttpHeaders.LOCATION));
        verify(service).completeLogin("authorization-code", "state-token", "browser-binding");
        verify(sessions).createSession(user, "127.0.0.1");
    }

    @Test
    void externalTicketRouteBindsNamedQueryParameter() throws Exception {
        User user = new User();
        UnifiedAuthServiceImpl.OidcCallbackResult result =
                new UnifiedAuthServiceImpl.OidcCallbackResult(user, false);
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.completeExternalTicket("single-use-ticket")).thenReturn(result);
        when(service.callbackRedirect(result)).thenReturn(FRONTEND_URL);
        SessionService sessions = mock(SessionService.class);
        when(sessions.createSession(user, "127.0.0.1")).thenReturn("normal-session-id");
        UnifiedAuthController controller = controller(service, sessions);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        var response = mvc.perform(get("/api/v1/unified-auth/sso/entry")
                        .param("ticket", "single-use-ticket")
                        .secure(true))
                .andReturn()
                .getResponse();

        assertEquals(HttpStatus.FOUND.value(), response.getStatus());
        assertEquals(FRONTEND_URL, response.getHeader(HttpHeaders.LOCATION));
        verify(service).completeExternalTicket("single-use-ticket");
        verify(sessions).createSession(user, "127.0.0.1");
    }

    @Test
    void unbindRouteBindsNamedIdentityPathVariable() throws Exception {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        UnifiedAuthController controller = controller(service, mock(SessionService.class));
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        User admin = new User();
        admin.setUserType(UserType.ADMIN_USER);
        when(currentUserProvider.getCurrentUser()).thenReturn(admin);
        ReflectionTestUtils.setField(controller, "currentUserProvider", currentUserProvider);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        var response = mvc.perform(delete("/api/v1/unified-auth/mappings/73"))
                .andReturn()
                .getResponse();

        assertEquals(HttpStatus.OK.value(), response.getStatus());
        verify(service).unbindMapping(73L);
    }

    private static UnifiedAuthController controller(
            UnifiedAuthServiceImpl service, SessionService sessions) {
        UnifiedAuthController controller = new UnifiedAuthController();
        ReflectionTestUtils.setField(controller, "unifiedAuthService", service);
        ReflectionTestUtils.setField(controller, "currentUserProvider", mock(CurrentUserProvider.class));
        ReflectionTestUtils.setField(controller, "sessionService", sessions);
        return controller;
    }

    private static HttpServletRequest secureRequest(Cookie... cookies) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.isSecure()).thenReturn(true);
        when(request.getCookies()).thenReturn(cookies.length == 0 ? null : cookies);
        return request;
    }
}
