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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedAuthServiceImplTest {

    private static final String CALLBACK_URL =
            "https://web.example.com/api/v1/unified-auth/oidc/callback/seatunnel";
    private static final String FRONTEND_URL = "/bi";

    @BeforeAll
    static void initializeIdentityTableMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UnifiedAuthIdentity.class);
        TableInfoHelper.initTableInfo(assistant, UnifiedAuthConfig.class);
        TableInfoHelper.initTableInfo(assistant, User.class);
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
    void oidcIssuerComparisonIsExactAndDiscoveryRemovesOnlyTheFinalSeparator() throws Exception {
        UnifiedAuthServiceImpl service = service();

        assertEquals("https://identity.example.com/", invoke(service, "normalizeIssuer",
                "https://identity.example.com/"));
        assertEquals(URI.create("https://identity.example.com/.well-known/openid-configuration"),
                invoke(service, "discoveryUri", "https://identity.example.com/"));
        Method exactIssuer = UnifiedAuthServiceImpl.class.getDeclaredMethod(
                "requireExactIssuer", String.class, String.class, String.class);
        exactIssuer.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> exactIssuer.invoke(service, "https://identity.example.com/",
                        "https://identity.example.com", "issuer mismatch"));
    }

    @Test
    void oidcProviderStatusMatchesTheTrustedCallbackAndSecretRequirements() {
        UnifiedAuthConfig config = new UnifiedAuthConfig();
        config.setEnabled(true);
        config.setClientSecretEnc("encrypted-secret");
        config.setRedirectUri(CALLBACK_URL);
        UnifiedAuthServiceImpl service = service(CALLBACK_URL, "PASSWORD");
        UnifiedAuthConfigMapper configMapper = mock(UnifiedAuthConfigMapper.class);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(config);
        ReflectionTestUtils.setField(service, "configMapper", configMapper);

        assertTrue(service.getProvider().isEnabled());
        config.setRedirectUri("https://old.example.com/api/v1/unified-auth/oidc/callback/seatunnel");
        assertFalse(service.getProvider().isEnabled());
        config.setRedirectUri(CALLBACK_URL);
        config.setClientSecretEnc(null);
        assertFalse(service.getProvider().isEnabled());
    }

    @Test
    void oidcCodeExchangeUsesTheFixedEnvironmentCallback() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id_token\":\"test-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            UnifiedAuthServiceImpl service = service(CALLBACK_URL, "PASSWORD");
            UnifiedAuthConfig config = new UnifiedAuthConfig();
            config.setClientId("seatunnel-web");
            config.setRedirectUri("https://old.example.com/api/v1/unified-auth/oidc/callback/seatunnel");
            Class<?> metadataType = Class.forName(UnifiedAuthServiceImpl.class.getName() + "$ProviderMetadata");
            Constructor<?> metadataConstructor = metadataType.getDeclaredConstructor(URI.class, URI.class, URI.class);
            metadataConstructor.setAccessible(true);
            Object metadata = metadataConstructor.newInstance(
                    URI.create("http://127.0.0.1/authorize"),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
                    URI.create("http://127.0.0.1/jwks"));
            Method exchangeCode = UnifiedAuthServiceImpl.class.getDeclaredMethod(
                    "exchangeCode", metadataType, UnifiedAuthConfig.class,
                    String.class, String.class, String.class);
            exchangeCode.setAccessible(true);

            exchangeCode.invoke(service, metadata, config, "secret", "code", "verifier");

            assertTrue(requestBody.get().contains("redirect_uri="
                    + java.net.URLEncoder.encode(CALLBACK_URL, StandardCharsets.UTF_8)));
            assertFalse(requestBody.get().contains(java.net.URLEncoder.encode(
                    config.getRedirectUri(), StandardCharsets.UTF_8)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void identityProfileRefreshDoesNotUpdateTheAdministratorBinding() throws Exception {
        UnifiedAuthServiceImpl service = service();
        UnifiedAuthIdentityMapper identityMapper = mock(UnifiedAuthIdentityMapper.class);
        UnifiedAuthIdentity existing = identity(73L, 9308000);
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);

        Method findOrCreate = UnifiedAuthServiceImpl.class.getDeclaredMethod(
                "findOrCreateIdentity", String.class, String.class, String.class, String.class, String.class);
        findOrCreate.setAccessible(true);
        findOrCreate.invoke(service, "OIDC", "https://identity.example.com",
                "subject-73", "new@example.com", "Updated name");

        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(identityMapper).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<?> updateWrapper = (LambdaUpdateWrapper<?>) wrapperCaptor.getValue();
        assertTrue(updateWrapper.getSqlSet().contains("email"));
        assertTrue(updateWrapper.getSqlSet().contains("display_name"));
        assertFalse(updateWrapper.getSqlSet().contains("user_id"));
        verify(identityMapper, never()).updateById(existing);
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
    void identityInsertRaceRollsBackAndReloadsExistingBinding() throws Exception {
        UnifiedAuthServiceImpl service = service();
        UnifiedAuthIdentityMapper identityMapper = mock(UnifiedAuthIdentityMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UnifiedAuthIdentity existing = identity(73L, 9308000);
        User boundUser = new User();
        boundUser.setId(9308000);
        boundUser.setState(Flag.YES.ordinal());
        boundUser.setUserType(UserType.GENERAL_USER);
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null, existing);
        when(identityMapper.insert(any(UnifiedAuthIdentity.class)))
                .thenThrow(new DuplicateKeyException("identity key already exists"));
        when(userMapper.selectById(9308000)).thenReturn(boundUser);
        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        UnifiedAuthConfig config = new UnifiedAuthConfig();
        config.setAutoProvisionGeneralUser(true);

        Method resolve = UnifiedAuthServiceImpl.class.getDeclaredMethod("resolveUserForIdentity",
                UnifiedAuthConfig.class, String.class, String.class, String.class, String.class, String.class);
        resolve.setAccessible(true);
        UnifiedAuthServiceImpl.OidcCallbackResult result =
                (UnifiedAuthServiceImpl.OidcCallbackResult) resolve.invoke(
                        service, config, "OIDC", "https://identity.example.com", "subject-73", null, null);

        assertFalse(result.pending());
        assertSame(boundUser, result.user());
        verify(userMapper, never()).insert(any(User.class));
        verify((PlatformTransactionManager) ReflectionTestUtils.getField(service, "transactionManager"))
                .rollback(any());
    }

    @Test
    void losingAutoProvisionBindingRaceRollsBackNewUserAndUsesLatestBinding() throws Exception {
        UnifiedAuthServiceImpl service = service();
        UnifiedAuthIdentityMapper identityMapper = mock(UnifiedAuthIdentityMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        UnifiedAuthIdentity latestIdentity = identity(73L, 9308001);
        User boundUser = new User();
        boundUser.setId(9308001);
        boundUser.setState(Flag.YES.ordinal());
        boundUser.setUserType(UserType.GENERAL_USER);
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null, latestIdentity);
        when(identityMapper.insert(any(UnifiedAuthIdentity.class))).thenAnswer(invocation -> {
            ((UnifiedAuthIdentity) invocation.getArgument(0)).setId(73L);
            return 1;
        });
        when(identityMapper.update(isNull(), any(Wrapper.class))).thenReturn(0, 1);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setId(9308002);
            return 1;
        });
        when(userMapper.selectById(9308001)).thenReturn(boundUser);
        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        UnifiedAuthConfig config = new UnifiedAuthConfig();
        config.setAutoProvisionGeneralUser(true);

        Method resolve = UnifiedAuthServiceImpl.class.getDeclaredMethod("resolveUserForIdentity",
                UnifiedAuthConfig.class, String.class, String.class, String.class, String.class, String.class);
        resolve.setAccessible(true);
        UnifiedAuthServiceImpl.OidcCallbackResult result =
                (UnifiedAuthServiceImpl.OidcCallbackResult) resolve.invoke(
                        service, config, "OIDC", "https://identity.example.com", "subject-73", null, null);

        assertSame(boundUser, result.user());
        verify(userMapper).insert(any(User.class));
        verify((PlatformTransactionManager) ReflectionTestUtils.getField(service, "transactionManager"))
                .rollback(any());
    }

    @Test
    void autoProvisionUsesDatabaseGeneratedUserIdBeforeBindingIdentity() throws Exception {
        UnifiedAuthServiceImpl service = service();
        UnifiedAuthIdentityMapper identityMapper = mock(UnifiedAuthIdentityMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(identityMapper.insert(any(UnifiedAuthIdentity.class))).thenAnswer(invocation -> {
            ((UnifiedAuthIdentity) invocation.getArgument(0)).setId(73L);
            return 1;
        });
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User inserted = invocation.getArgument(0);
            assertNull(inserted.getId());
            inserted.setId(42);
            return 1;
        });
        when(identityMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        UnifiedAuthConfig config = new UnifiedAuthConfig();
        config.setAutoProvisionGeneralUser(true);

        Method resolve = UnifiedAuthServiceImpl.class.getDeclaredMethod("resolveUserForIdentity",
                UnifiedAuthConfig.class, String.class, String.class, String.class, String.class, String.class);
        resolve.setAccessible(true);
        UnifiedAuthServiceImpl.OidcCallbackResult result =
                (UnifiedAuthServiceImpl.OidcCallbackResult) resolve.invoke(
                        service, config, "OIDC", "https://identity.example.com", "subject-42", null, null);

        assertTrue(result.user().getId() > 0);
        assertEquals(42, result.user().getId());
        verify(userMapper).insert(any(User.class));
        verify(identityMapper).update(isNull(), any(Wrapper.class));
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
    void getTicketValidationPreservesRawQueryAndEncodesTicketValue() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/validate", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"success\":true,\"username\":\"subject-1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/validate?keep=%2Fvalue";
            UnifiedAuthConfig config = ticketConfig(endpoint);
            config.setTicketValidationMethod("GET");
            config.setTicketParameterName("externalTicket");
            UnifiedAuthServiceImpl service = serviceWithConfig(config);
            UnifiedAuthIdentityMapper identityMapper = mock(UnifiedAuthIdentityMapper.class);
            when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
            when(identityMapper.insert(any(UnifiedAuthIdentity.class))).thenAnswer(invocation -> {
                ((UnifiedAuthIdentity) invocation.getArgument(0)).setId(73L);
                return 1;
            });
            ReflectionTestUtils.setField(service, "identityMapper", identityMapper);

            var result = service.completeExternalTicket("a+b /ü?&=%");

            assertTrue(result.pending());
            assertEquals("keep=%2Fvalue&externalTicket=a%2Bb+%2F%C3%BC%3F%26%3D%25", rawQuery.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getTicketValidationRejectsConfiguredDuplicateParameter() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/validate", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/validate?%74icket=stale";
            UnifiedAuthConfig config = ticketConfig(endpoint);
            config.setTicketValidationMethod("GET");

            assertThrows(IllegalArgumentException.class,
                    () -> serviceWithConfig(config).completeExternalTicket("new-ticket"));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseBodyDeadlineCancelsAnIncompleteExchange() throws Exception {
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 64);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            responseStarted.countDown();
            try {
                releaseResponse.await(2, TimeUnit.SECONDS);
                exchange.getResponseBody().write(new byte[63]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                responseStarted.countDown();
            } catch (IOException ignored) {
                responseStarted.countDown();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/slow"))
                    .timeout(Duration.ofMillis(150))
                    .GET()
                    .build();
            long startedAt = System.nanoTime();

            assertThrows(HttpTimeoutException.class, () -> service().sendBoundedResponse(request));
            assertTrue(responseStarted.await(1, TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() < 1_500);
        } finally {
            releaseResponse.countDown();
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
    void startingAgainForOneBrowserKeepsOnlyTheNewestOidcState() throws Exception {
        UnifiedAuthServiceImpl service = service();
        Method register = UnifiedAuthServiceImpl.class.getDeclaredMethod(
                "registerLoginAttempt", String.class, String.class, String.class,
                String.class, long.class, long.class);
        register.setAccessible(true);
        long now = System.currentTimeMillis();

        String binding = (String) register.invoke(
                service, "first-state", "first-nonce", "first-verifier", null, 1L, now);
        String secondBinding = (String) register.invoke(
                service, "second-state", "second-nonce", "second-verifier", binding, 1L, now + 1);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, Object> attempts = (ConcurrentMap<String, Object>)
                ReflectionTestUtils.getField(service, "loginAttempts");

        assertEquals(binding, secondBinding);
        assertEquals(1, attempts.size());
        assertFalse(attempts.containsKey("first-state"));
        assertThrows(IllegalArgumentException.class,
                () -> service.completeLogin("old-code", "first-state", binding));
        assertTrue(attempts.containsKey("second-state"));
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
        UnifiedAuthServiceImpl service = new UnifiedAuthServiceImpl(callbackUrl, FRONTEND_URL, authenticationMode);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        return service;
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

    private static User activeGeneralUser(Integer id, String username) {
        User user = new User();
        user.setId(id);
        user.setUserName(username);
        user.setState(Flag.YES.ordinal());
        user.setUserType(UserType.GENERAL_USER);
        return user;
    }

    private static UnifiedAuthServiceImpl serviceWithConfig(UnifiedAuthConfig config) {
        UnifiedAuthServiceImpl service = service(null, "PASSWORD");
        UnifiedAuthConfigMapper configMapper = mock(UnifiedAuthConfigMapper.class);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(config);
        ReflectionTestUtils.setField(service, "configMapper", configMapper);
        return service;
    }
}
