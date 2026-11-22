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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), cookies.capture());
        List<String> setCookies = cookies.getAllValues();
        assertTrue(setCookies.stream().anyMatch(cookie -> cookie.contains(
                Constants.SESSION_ID + "=normal-session-id;") && cookie.contains("HttpOnly")
                && cookie.contains("Secure") && cookie.contains("SameSite=Lax")));
        assertEquals(1, setCookies.size());
        assertEquals("no-referrer", redirect.getHeaders().getFirst("Referrer-Policy"));
        assertTrue(redirect.getHeaders().getCacheControl().contains("no-store"));
        verify(service).completeLogin("authorization-code", "state-token", "browser-binding");
    }

    @Test
    void oidcStartSetsHttpOnlySameSiteBrowserBindingCookie() {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.beginLogin("existing-binding")).thenReturn(new UnifiedAuthServiceImpl.OidcLoginStart(
                "https://identity.example.com/authorize", "existing-binding", 600));
        UnifiedAuthController controller = controller(service, mock(SessionService.class));
        HttpServletRequest request = secureRequest(new Cookie("ST_OIDC_BROWSER_BINDING", "existing-binding"));
        when(request.isSecure()).thenReturn(false);

        var redirect = controller.startLogin(request);

        assertEquals(HttpStatus.FOUND, redirect.getStatusCode());
        assertEquals("https://identity.example.com/authorize", redirect.getHeaders().getLocation().toString());
        String bindingCookie = redirect.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(bindingCookie.startsWith("ST_OIDC_BROWSER_BINDING=existing-binding;"));
        assertTrue(bindingCookie.contains("HttpOnly"));
        assertTrue(bindingCookie.contains("Secure"));
        assertTrue(bindingCookie.contains("SameSite=Lax"));
        assertTrue(bindingCookie.contains("Max-Age=600"));
        verify(service).beginLogin("existing-binding");
    }

    @Test
    void externalTicketEntryStagesConfirmationWithoutCreatingASession() {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.prepareExternalTicketLogin("single-use-ticket", "existing-binding")).thenReturn(
                new UnifiedAuthServiceImpl.TicketLoginStart("existing-binding", 60));
        SessionService sessions = mock(SessionService.class);
        HttpServletRequest request = secureRequest(new Cookie("ST_TICKET_BROWSER_BINDING", "existing-binding"));
        when(request.isSecure()).thenReturn(false);
        when(request.getParameterValues("ticket")).thenReturn(new String[] {"single-use-ticket"});
        UnifiedAuthController controller = controller(service, sessions);

        var redirect = controller.enterExternalTicketSession(request);

        assertEquals(HttpStatus.FOUND, redirect.getStatusCode());
        assertEquals("/login", redirect.getHeaders().getLocation().toString());
        String bindingCookie = redirect.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(bindingCookie.startsWith("ST_TICKET_BROWSER_BINDING=existing-binding;"));
        assertTrue(bindingCookie.contains("HttpOnly"));
        assertTrue(bindingCookie.contains("Secure"));
        assertTrue(bindingCookie.contains("SameSite=Lax"));
        assertTrue(bindingCookie.contains("Max-Age=60"));
        assertEquals("no-referrer", redirect.getHeaders().getFirst("Referrer-Policy"));
        assertTrue(redirect.getHeaders().getCacheControl().contains("no-store"));
        verify(service).prepareExternalTicketLogin("single-use-ticket", "existing-binding");
        verify(sessions, never()).createSession(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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
    void externalTicketRouteStagesTheTicketAndRedirectsWithoutQueryData() throws Exception {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.prepareExternalTicketLogin("single-use-ticket", null)).thenReturn(
                new UnifiedAuthServiceImpl.TicketLoginStart("browser-binding", 60));
        SessionService sessions = mock(SessionService.class);
        UnifiedAuthController controller = controller(service, sessions);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        var response = mvc.perform(get("/api/v1/unified-auth/sso/entry")
                        .param("ticket", "single-use-ticket")
                        .secure(true))
                .andReturn()
                .getResponse();

        assertEquals(HttpStatus.FOUND.value(), response.getStatus());
        assertEquals("/login", response.getHeader(HttpHeaders.LOCATION));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertTrue(response.getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
        verify(service).prepareExternalTicketLogin("single-use-ticket", null);
        verify(sessions, never()).createSession(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidTicketEntryDoesNotReplaceAnExistingBrowserBinding() throws Exception {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.prepareExternalTicketLogin("invalid-ticket", "current-binding"))
                .thenThrow(new IllegalArgumentException("ticket rejected"));
        UnifiedAuthController controller = controller(service, mock(SessionService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        var response = mvc.perform(get("/api/v1/unified-auth/sso/entry")
                        .param("ticket", "invalid-ticket")
                        .cookie(new Cookie("ST_TICKET_BROWSER_BINDING", "current-binding")))
                .andReturn()
                .getResponse();

        assertEquals(HttpStatus.FOUND.value(), response.getStatus());
        assertEquals("/login?ticketLogin=failed", response.getHeader(HttpHeaders.LOCATION));
        assertNull(response.getHeader(HttpHeaders.SET_COOKIE));
        verify(service).prepareExternalTicketLogin("invalid-ticket", "current-binding");
    }

    @Test
    void ticketPendingAndConfirmRequireSameOriginFetchMetadata() {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        SessionService sessions = mock(SessionService.class);
        UnifiedAuthController controller = controller(service, sessions);
        HttpServletRequest request = secureRequest(new Cookie("ST_TICKET_BROWSER_BINDING", "binding"));

        assertThrows(ResponseStatusException.class, () -> controller.pendingExternalTicketLogin(request));
        assertThrows(ResponseStatusException.class, () -> controller.confirmExternalTicketLogin(
                new UnifiedAuthController.TicketConfirmationRequest("token"), request,
                mock(HttpServletResponse.class)));
        assertThrows(ResponseStatusException.class, () -> controller.cancelExternalTicketLogin(
                new UnifiedAuthController.TicketCancellationRequest("cancel-token"), request));
        verifyNoInteractions(service, sessions);
    }

    @Test
    void sameOriginPendingReadReturnsNoStoreConfirmationDetails() {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.getPendingTicketLogin("browser-binding")).thenReturn(
                new UnifiedAuthServiceImpl.TicketLoginPending(
                        "External account", "external@example.com", "local-user",
                        "confirmation-token", "cancellation-token", true));
        UnifiedAuthController controller = controller(service, mock(SessionService.class));
        HttpServletRequest request = secureRequest(new Cookie("ST_TICKET_BROWSER_BINDING", "browser-binding"));
        when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");

        var response = controller.pendingExternalTicketLogin(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(response.getBody() != null);
        verify(service).getPendingTicketLogin("browser-binding");
    }

    @Test
    void sameOriginTicketConfirmationCreatesSecureSessionWithoutClearingAnUnrelatedBinding() {
        User user = new User();
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.confirmExternalTicketLogin("browser-binding", "confirmation-token")).thenReturn(user);
        SessionService sessions = mock(SessionService.class);
        when(sessions.createSession(user, "127.0.0.1")).thenReturn("normal-session-id");
        UnifiedAuthController controller = controller(service, sessions);
        HttpServletRequest request = secureRequest(new Cookie("ST_TICKET_BROWSER_BINDING", "browser-binding"));
        when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
        HttpServletResponse response = mock(HttpServletResponse.class);
        ReflectionTestUtils.setField(controller, "secureCookies", false);

        var result = controller.confirmExternalTicketLogin(
                new UnifiedAuthController.TicketConfirmationRequest("confirmation-token"), request, response);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("no-referrer", result.getHeaders().getFirst("Referrer-Policy"));
        ArgumentCaptor<String> cookies = ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq(HttpHeaders.SET_COOKIE), cookies.capture());
        assertTrue(cookies.getValue().contains(Constants.SESSION_ID + "=normal-session-id;")
                && cookies.getValue().contains("Secure"));
        assertNull(result.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        verify(service).confirmExternalTicketLogin("browser-binding", "confirmation-token");
    }

    @Test
    void cancellingCurrentTicketFlowDoesNotEmitAStaleCookieDeletion() {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.cancelExternalTicketLogin("browser-binding", "cancellation-token")).thenReturn(true);
        SessionService sessions = mock(SessionService.class);
        UnifiedAuthController controller = controller(service, sessions);
        HttpServletRequest request = secureRequest(new Cookie("ST_TICKET_BROWSER_BINDING", "browser-binding"));
        when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");

        var response = controller.cancelExternalTicketLogin(
                new UnifiedAuthController.TicketCancellationRequest("cancellation-token"), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        verify(service).cancelExternalTicketLogin("browser-binding", "cancellation-token");
        verify(sessions, never()).createSession(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleTicketCancelDoesNotClearTheCurrentBrowserBinding() {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.cancelExternalTicketLogin("current-binding", "old-cancellation-token")).thenReturn(false);
        UnifiedAuthController controller = controller(service, mock(SessionService.class));
        HttpServletRequest request = secureRequest(new Cookie("ST_TICKET_BROWSER_BINDING", "current-binding"));
        when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");

        var response = controller.cancelExternalTicketLogin(
                new UnifiedAuthController.TicketCancellationRequest("old-cancellation-token"), request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        verify(service).cancelExternalTicketLogin("current-binding", "old-cancellation-token");
    }

    @Test
    void invalidOidcCallbackDoesNotClearTheCookieForANewerFlow() {
        UnifiedAuthServiceImpl service = mock(UnifiedAuthServiceImpl.class);
        when(service.completeLogin("old-code", "old-state", "current-browser-binding"))
                .thenThrow(new IllegalArgumentException("state mismatch"));
        when(service.callbackFailureRedirect()).thenReturn(FRONTEND_URL + "?oidc=failed");
        HttpServletResponse response = mock(HttpServletResponse.class);
        UnifiedAuthController controller = controller(service, mock(SessionService.class));

        var redirect = controller.callback("old-code", "old-state", null,
                secureRequest(new Cookie("ST_OIDC_BROWSER_BINDING", "current-browser-binding")), response);

        assertEquals(FRONTEND_URL + "?oidc=failed", redirect.getHeaders().getLocation().toString());
        verify(response, never()).addHeader(eq(HttpHeaders.SET_COOKIE), org.mockito.ArgumentMatchers.anyString());
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
