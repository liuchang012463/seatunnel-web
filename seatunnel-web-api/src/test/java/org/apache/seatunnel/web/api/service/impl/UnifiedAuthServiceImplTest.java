package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.sun.net.httpserver.HttpServer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.seatunnel.web.common.enums.Flag;
import org.apache.seatunnel.web.common.enums.UserType;
import org.apache.seatunnel.web.dao.entity.UnifiedAuthConfig;
import org.apache.seatunnel.web.dao.entity.UnifiedAuthIdentity;
import org.apache.seatunnel.web.dao.entity.User;
import org.apache.seatunnel.web.dao.mapper.UnifiedAuthConfigMapper;
import org.apache.seatunnel.web.dao.mapper.UnifiedAuthIdentityMapper;
import org.apache.seatunnel.web.dao.mapper.UserMapper;
import org.apache.seatunnel.web.spi.bean.dto.UnifiedAuthConfigDTO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthConfigVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedAuthServiceImplTest {

    private static final String CALLBACK_URL =
            "https://web.example.com/api/v1/unified-auth/oidc/callback/seatunnel";
    private static final String FRONTEND_URL = "/bi";

    @BeforeAll
    static void initializeIdentityTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UnifiedAuthIdentity.class);
    }

    @Test
    void configRejectsRedirectUriOutsideTheTrustedCallback() {
        UnifiedAuthServiceImpl service = service();
        UnifiedAuthConfigDTO request = new UnifiedAuthConfigDTO();
        request.setIssuer("https://identity.example.com");
        request.setClientId("seatunnel-web");
        request.setRedirectUri("https://attacker.example.com/api/v1/unified-auth/oidc/callback/seatunnel");

        assertThrows(IllegalArgumentException.class, () -> service.saveConfig(request));
    }

    @Test
    void callbackRedirectsDoNotCarryAuthorizationParameters() {
        UnifiedAuthServiceImpl service = service();

        assertEquals(FRONTEND_URL,
                service.callbackRedirect(new UnifiedAuthServiceImpl.OidcCallbackResult(new User(), false)));
        assertEquals(FRONTEND_URL + "?oidc=pending",
                service.callbackRedirect(new UnifiedAuthServiceImpl.OidcCallbackResult(null, true)));
        assertEquals(FRONTEND_URL + "?oidc=failed", service.callbackFailureRedirect());
    }

    @Test
    void unbindMappingExplicitlyClearsNullableUserIdWithCompareAndSet() {
        UnifiedAuthServiceImpl service = service();
        UnifiedAuthIdentityMapper identityMapper = mock(UnifiedAuthIdentityMapper.class);
        UnifiedAuthIdentity identity = identity(73L, 9308000);
        when(identityMapper.selectById(73L)).thenReturn(identity);
        when(identityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);

        service.unbindMapping(73L);

        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(identityMapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<?> updateWrapper = (LambdaUpdateWrapper<?>) wrapperCaptor.getValue();
        var userIdSet = Pattern.compile("user_id\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}",
                Pattern.CASE_INSENSITIVE).matcher(updateWrapper.getSqlSet());
        assertTrue(userIdSet.find(), updateWrapper.getSqlSet());
        assertNull(updateWrapper.getParamNameValuePairs().get(userIdSet.group(1)));
        assertTrue(updateWrapper.getSqlSegment().contains("id ="));
        assertTrue(updateWrapper.getSqlSegment().contains("user_id ="));
        assertTrue(updateWrapper.getParamNameValuePairs().containsValue(73L));
        assertTrue(updateWrapper.getParamNameValuePairs().containsValue(9308000));
    }

    @Test
    void staleBindingCleanupUsesLatestBindingWhenCompareAndSetLosesRace() throws Exception {
        UnifiedAuthServiceImpl service = service();
        UnifiedAuthIdentityMapper identityMapper = mock(UnifiedAuthIdentityMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UnifiedAuthIdentity staleIdentity = identity(73L, 9308000);
        UnifiedAuthIdentity latestIdentity = identity(73L, 9308001);
        User latestUser = new User();
        latestUser.setId(9308001);
        latestUser.setUserType(UserType.GENERAL_USER);
        latestUser.setState(Flag.YES.ordinal());
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(staleIdentity);
        when(identityMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        when(identityMapper.selectById(73L)).thenReturn(latestIdentity);
        when(userMapper.selectById(9308000)).thenReturn(null);
        when(userMapper.selectById(9308001)).thenReturn(latestUser);
        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        UnifiedAuthConfig config = new UnifiedAuthConfig();
        config.setAutoProvisionGeneralUser(false);

        Method resolve = UnifiedAuthServiceImpl.class.getDeclaredMethod("resolveUserForIdentity",
                UnifiedAuthConfig.class, String.class, String.class, String.class, String.class, String.class);
        resolve.setAccessible(true);
        UnifiedAuthServiceImpl.OidcCallbackResult result =
                (UnifiedAuthServiceImpl.OidcCallbackResult) resolve.invoke(
                        service, config, "OIDC", "https://identity.example.com", "subject-73", null, null);

        assertFalse(result.pending());
        assertSame(latestUser, result.user());
        verify(userMapper).selectById(9308000);
        verify(userMapper).selectById(9308001);
    }

    @Test
    void ticketOnlyConfigCanBeSavedWithoutOidcProviderFields() {
        UnifiedAuthServiceImpl service = service(null, "PASSWORD");
        UnifiedAuthConfigMapper configMapper = mock(UnifiedAuthConfigMapper.class);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        ReflectionTestUtils.setField(service, "configMapper", configMapper);

        UnifiedAuthConfigDTO request = new UnifiedAuthConfigDTO();
        request.setEnabled(false);
        request.setTicketEnabled(true);
        request.setTicketValidationEndpoint("https://sso.example.com/api/ticket/validate");
        request.setTicketValidationMethod("POST");
        request.setTicketIssuer("https://sso.example.com");
        request.setTicketParameterName("ticket");
        request.setTicketSuccessField("data.valid");
        request.setTicketSuccessValue("true");
        request.setTicketUsernameField("data.username");

        UnifiedAuthConfigVO saved = service.saveConfig(request);

        assertNull(saved.getIssuer());
        assertNull(saved.getClientId());
        assertEquals("https://sso.example.com", saved.getTicketIssuer());
        assertEquals("ticket", saved.getTicketParameterName());
        assertEquals("data.valid", saved.getTicketSuccessField());
        assertEquals("data.username", saved.getTicketUsernameField());
        assertNull(saved.getTicketLoginEntryUrl());
        verify(configMapper).insert(any(UnifiedAuthConfig.class));
    }

    @Test
    void loopbackTicketLoginEntryCanBeConfiguredForDevelopment() {
        UnifiedAuthServiceImpl service = service(null, "PASSWORD");
        UnifiedAuthConfigMapper configMapper = mock(UnifiedAuthConfigMapper.class);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        ReflectionTestUtils.setField(service, "configMapper", configMapper);
        UnifiedAuthConfigDTO request = ticketOnlyRequest();
        request.setTicketLoginEntryUrl("http://localhost:8081/portal/login");

        UnifiedAuthConfigVO saved = service.saveConfig(request);

        assertEquals("http://localhost:8081/portal/login", saved.getTicketLoginEntryUrl());
    }

    @Test
    void ticketLoginEntryRejectsLanPlainHttp() {
        UnifiedAuthServiceImpl service = service(null, "PASSWORD");
        UnifiedAuthConfigDTO request = ticketOnlyRequest();
        request.setTicketLoginEntryUrl("http://192.168.100.95/portal/login");

        assertThrows(IllegalArgumentException.class, () -> service.saveConfig(request));
    }

    @Test
    void ticketOnlyProviderExposesConfiguredEntryWithoutEnablingOidc() {
        UnifiedAuthConfig config = ticketConfig("https://sso.example.com/validate");
        config.setTicketLoginEntryUrl("https://sso.example.com/login");
        UnifiedAuthServiceImpl service = serviceWithConfig(config);

        var provider = service.getProvider();

        assertFalse(provider.isEnabled());
        assertTrue(provider.isTicketLoginEnabled());
        assertEquals("https://sso.example.com/login", provider.getTicketLoginEntryUrl());
    }

    @Test
    void failedTicketValidationReleasesReservationForRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/validate", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/validate";
            UnifiedAuthServiceImpl service = serviceWithConfig(ticketConfig(endpoint));

            assertThrows(IllegalArgumentException.class,
                    () -> service.completeExternalTicket("retryable-ticket"));
            assertThrows(IllegalArgumentException.class,
                    () -> service.completeExternalTicket("retryable-ticket"));

            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulTicketIsConsumedEvenWhenIdentityNeedsBinding() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/validate", exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"success\":true,\"username\":\"subject-1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/validate";
            UnifiedAuthConfig config = ticketConfig(endpoint);
            config.setTicketParameterName("externalTicket");
            UnifiedAuthServiceImpl service = serviceWithConfig(config);
            UnifiedAuthIdentityMapper identityMapper = mock(UnifiedAuthIdentityMapper.class);
            when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
            ReflectionTestUtils.setField(service, "identityMapper", identityMapper);

            var firstLogin = service.completeExternalTicket("one-use-ticket");
            assertTrue(firstLogin.pending());
            assertThrows(IllegalArgumentException.class,
                    () -> service.completeExternalTicket("one-use-ticket"));

            assertEquals("POST", method.get());
            assertEquals("externalTicket=one-use-ticket", requestBody.get());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pendingTicketValidationConcurrencyIsBounded() {
        UnifiedAuthServiceImpl service = service();
        List<Object> reservations = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            reservations.add(ReflectionTestUtils.invokeMethod(service, "reserveTicket", "pending-ticket-" + index));
        }

        assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "reserveTicket", "overflow-ticket"));
        reservations.forEach(reservation ->
                ReflectionTestUtils.invokeMethod(service, "releaseTicketReservation", reservation));
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "reserveTicket", "after-release-ticket"));
    }

    @Test
    void oidcConnectionTestRequiresOidcInputs() {
        var result = service().testConnection(new UnifiedAuthConfigDTO());

        assertFalse(result.isConnected());
        assertTrue(result.getMessage().contains("OIDC Provider"));
    }

    @Test
    void oidcStateIsBrowserBoundAndConsumedAfterMatchingCallback() throws Exception {
        UnifiedAuthServiceImpl service = service();
        UnifiedAuthConfigMapper configMapper = mock(UnifiedAuthConfigMapper.class);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        ReflectionTestUtils.setField(service, "configMapper", configMapper);
        String browserBinding = "browser-binding-token";
        String bindingHash = (String) invoke(service, "hashBrowserBinding", browserBinding);
        long createdAt = System.currentTimeMillis();
        Class<?> attemptType = Class.forName(UnifiedAuthServiceImpl.class.getName() + "$LoginAttempt");
        Constructor<?> attemptConstructor = attemptType.getDeclaredConstructor(
                String.class, String.class, String.class, long.class, long.class);
        attemptConstructor.setAccessible(true);
        Object attempt = attemptConstructor.newInstance("nonce", "verifier", bindingHash, 1L, createdAt);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, Object> attempts = (ConcurrentMap<String, Object>)
                ReflectionTestUtils.getField(service, "loginAttempts");
        attempts.put("one-use-state", attempt);

        IllegalArgumentException crossBrowser = assertThrows(IllegalArgumentException.class,
                () -> service.completeLogin("code", "one-use-state", "other-browser"));
        assertTrue(crossBrowser.getMessage().contains("浏览器"));
        assertTrue(attempts.containsKey("one-use-state"));

        IllegalArgumentException reachedProviderCheck = assertThrows(IllegalArgumentException.class,
                () -> service.completeLogin("code", "one-use-state", browserBinding));
        assertTrue(reachedProviderCheck.getMessage().contains("尚未启用"));
        assertFalse(attempts.containsKey("one-use-state"));

        IllegalArgumentException replay = assertThrows(IllegalArgumentException.class,
                () -> service.completeLogin("code", "one-use-state", browserBinding));
        assertTrue(replay.getMessage().contains("失效"));
    }

    @Test
    void idTokenRequiresIssuedAt() {
        long now = System.currentTimeMillis();
        JWTClaimsSet missingIssuedAt = new JWTClaimsSet.Builder()
                .expirationTime(new Date(now + 60_000))
                .build();
        JWTClaimsSet validTimes = new JWTClaimsSet.Builder()
                .issueTime(new Date(now))
                .expirationTime(new Date(now + 60_000))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> UnifiedAuthServiceImpl.validateTokenTimes(missingIssuedAt, now));
        assertDoesNotThrow(() -> UnifiedAuthServiceImpl.validateTokenTimes(validTimes, now));
    }

    @Test
    void idTokenRejectsMismatchingAuthorizedPartyWheneverPresent() {
        JWTClaimsSet matchingAuthorizedParty = new JWTClaimsSet.Builder()
                .audience("seatunnel-web")
                .claim("azp", "seatunnel-web")
                .build();
        JWTClaimsSet mismatchingAuthorizedParty = new JWTClaimsSet.Builder()
                .audience("seatunnel-web")
                .claim("azp", "another-client")
                .build();
        JWTClaimsSet multiAudienceWithoutAuthorizedParty = new JWTClaimsSet.Builder()
                .audience(java.util.List.of("seatunnel-web", "another-client"))
                .build();

        assertDoesNotThrow(() -> UnifiedAuthServiceImpl.validateAudience(
                matchingAuthorizedParty, "seatunnel-web"));
        assertThrows(IllegalArgumentException.class, () -> UnifiedAuthServiceImpl.validateAudience(
                mismatchingAuthorizedParty, "seatunnel-web"));
        assertThrows(IllegalArgumentException.class, () -> UnifiedAuthServiceImpl.validateAudience(
                multiAudienceWithoutAuthorizedParty, "seatunnel-web"));
    }

    private static Object invoke(Object target, String methodName, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static UnifiedAuthServiceImpl service() {
        return service(CALLBACK_URL, "PASSWORD");
    }

    private static UnifiedAuthServiceImpl service(String callbackUrl, String authenticationMode) {
        return new UnifiedAuthServiceImpl(callbackUrl, FRONTEND_URL, authenticationMode);
    }

    private static UnifiedAuthConfigDTO ticketOnlyRequest() {
        UnifiedAuthConfigDTO request = new UnifiedAuthConfigDTO();
        request.setEnabled(false);
        request.setTicketEnabled(true);
        request.setTicketValidationEndpoint("https://sso.example.com/api/ticket/validate");
        request.setTicketValidationMethod("POST");
        request.setTicketIssuer("https://sso.example.com");
        request.setTicketParameterName("ticket");
        request.setTicketSuccessField("data.valid");
        request.setTicketSuccessValue("true");
        request.setTicketUsernameField("data.username");
        return request;
    }

    private static UnifiedAuthConfig ticketConfig(String validationEndpoint) {
        UnifiedAuthConfig config = new UnifiedAuthConfig();
        config.setTicketEnabled(true);
        config.setTicketValidationEndpoint(validationEndpoint);
        config.setTicketValidationMethod("POST");
        config.setTicketIssuer("https://sso.example.com");
        config.setTicketParameterName("ticket");
        config.setTicketSuccessField("success");
        config.setTicketSuccessValue("true");
        config.setTicketUsernameField("username");
        config.setTicketResultEmailPointer("/email");
        config.setTicketResultDisplayNamePointer("/name");
        return config;
    }

    private static UnifiedAuthIdentity identity(Long id, Integer userId) {
        UnifiedAuthIdentity identity = new UnifiedAuthIdentity();
        identity.setId(id);
        identity.setIdentityKey("oidc-key-" + id);
        identity.setIdentitySource("OIDC");
        identity.setIssuer("https://identity.example.com");
        identity.setSubject("subject-" + id);
        identity.setUserId(userId);
        return identity;
    }

    private static UnifiedAuthServiceImpl serviceWithConfig(UnifiedAuthConfig config) {
        UnifiedAuthServiceImpl service = service(null, "PASSWORD");
        UnifiedAuthConfigMapper configMapper = mock(UnifiedAuthConfigMapper.class);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(config);
        ReflectionTestUtils.setField(service, "configMapper", configMapper);
        return service;
    }
}
