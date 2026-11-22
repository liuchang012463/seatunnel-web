package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonPointer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.Flag;
import org.apache.seatunnel.web.common.enums.UserType;
import org.apache.seatunnel.web.dao.entity.UnifiedAuthConfig;
import org.apache.seatunnel.web.dao.entity.UnifiedAuthIdentity;
import org.apache.seatunnel.web.dao.entity.User;
import org.apache.seatunnel.web.dao.mapper.UnifiedAuthConfigMapper;
import org.apache.seatunnel.web.dao.mapper.UnifiedAuthIdentityMapper;
import org.apache.seatunnel.web.dao.mapper.UserMapper;
import org.apache.seatunnel.web.spi.bean.dto.UnifiedAuthConfigDTO;
import org.apache.seatunnel.web.spi.bean.dto.UnifiedAuthMappingDTO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthConfigVO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthConnectTestVO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthIdentityVO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthLocalUserVO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthProviderVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** OIDC code-flow verification and the administrator-owned local identity bindings. */
@Slf4j
@Service
public class UnifiedAuthServiceImpl {

    private static final String CONFIG_KEY = "OIDC";
    private static final String CALLBACK_PATH = "/api/v1/unified-auth/oidc/callback/seatunnel";
    private static final long STATE_TTL_MS = Duration.ofMinutes(10).toMillis();
    private static final long TICKET_REPLAY_TTL_MS = Duration.ofMinutes(10).toMillis();
    private static final long TICKET_PENDING_TTL_MS = Duration.ofMinutes(1).toMillis();
    private static final int MAX_PENDING_TICKET_LOGINS = 256;
    private static final int MAX_PENDING_LOGINS = 5_000;
    private static final int MAX_TICKET_REPLAY_HASHES = 50_000;
    private static final int MAX_PENDING_TICKET_VALIDATIONS = 64;
    private static final long CLOCK_SKEW_SECONDS = 60L;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String callbackUrl;
    private final String frontendPath;
    private final String authenticationMode;

    @Resource
    private UnifiedAuthConfigMapper configMapper;
    @Resource
    private UnifiedAuthIdentityMapper identityMapper;
    @Resource
    private UserMapper userMapper;
    @Resource
    private UnifiedAuthSecretCipher secretCipher;

    private final ConcurrentMap<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();
    private final Object loginAttemptLock = new Object();
    private final Object ticketReplayLock = new Object();
    private final Map<String, TicketReplayEntry> ticketReplayHashes = new HashMap<>();
    private int pendingTicketValidations;
    private final ConcurrentMap<String, PendingTicketLogin> pendingTicketLogins = new ConcurrentHashMap<>();
    private final Object pendingTicketLoginLock = new Object();

    @Resource
    private PlatformTransactionManager transactionManager;

    public UnifiedAuthServiceImpl(
            @Value("${unified-auth.callback-url:}") String callbackUrl,
            @Value("${unified-auth.frontend-path:/bi}") String frontendPath,
            @Value("${security.authentication.mode:DEV_BYPASS}") String authenticationMode) {
        String trustedCallback = StringUtils.trimToNull(callbackUrl);
        this.callbackUrl = trustedCallback == null ? null : validateRedirectUri(trustedCallback, CALLBACK_PATH);
        this.frontendPath = validateFrontendPath(frontendPath);
        this.authenticationMode = StringUtils.defaultIfBlank(authenticationMode, "DEV_BYPASS").trim();
    }

    public UnifiedAuthConfigVO getConfig() {
        UnifiedAuthConfigVO vo = toConfigVO(configMapper.selectOne(new LambdaQueryWrapper<UnifiedAuthConfig>()
                .eq(UnifiedAuthConfig::getConfigKey, CONFIG_KEY)));
        vo.setAuthenticationMode(authenticationMode);
        vo.setTrustedCallbackConfigured(callbackUrl != null);
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public UnifiedAuthConfigVO saveConfig(UnifiedAuthConfigDTO request) {
        validateConfigRequest(request);
        boolean oidcEnabled = Boolean.TRUE.equals(request.getEnabled());
        boolean ticketEnabled = Boolean.TRUE.equals(request.getTicketEnabled());
        String issuer = StringUtils.trimToNull(request.getIssuer());
        if (issuer != null) {
            issuer = normalizeIssuer(issuer);
        }
        String clientId = StringUtils.trimToNull(request.getClientId());
        String ticketLoginEntryUrl = StringUtils.trimToNull(request.getTicketLoginEntryUrl());
        if (ticketLoginEntryUrl != null) {
            ticketLoginEntryUrl = validateTicketLoginEntryUrl(ticketLoginEntryUrl);
        }
        if (isDevBypass() && (oidcEnabled || ticketEnabled)) {
            throw invalid("当前使用 DEV_BYPASS，不能启用统一认证；请先切换为 PASSWORD 模式");
        }
        if (oidcEnabled && callbackUrl == null) {
            throw invalid("启用 OIDC 前请通过 UNIFIED_AUTH_CALLBACK_URL 配置可信回调地址");
        }
        String requestedRedirect = StringUtils.trimToNull(request.getRedirectUri());
        String redirectUri = callbackUrl == null
                ? null
                : validateRedirectUri(StringUtils.defaultIfBlank(requestedRedirect, callbackUrl), CALLBACK_PATH);
        if (redirectUri != null && !callbackUrl.equals(redirectUri)) {
            throw invalid("Redirect URI 必须与服务端配置的可信回调地址一致");
        }
        if (oidcEnabled) {
            discoverAndValidate(issuer);
        }
        if (ticketEnabled) {
            validateTicketConfig(request);
            validateTicketEndpointParameter(request);
        }

        UnifiedAuthConfig current = currentConfig();
        // Ticket-only deployments can omit OIDC details; retain saved OIDC
        // values when an administrator disables OIDC without clearing them.
        if (issuer == null && current != null) {
            issuer = current.getIssuer();
        }
        if (clientId == null && current != null) {
            clientId = current.getClientId();
        }
        String submittedSecret = StringUtils.trimToNull(request.getClientSecret());
        String encryptedSecret = submittedSecret == null
                ? current == null ? null : current.getClientSecretEnc()
                : secretCipher.encrypt(submittedSecret);
        if (Boolean.TRUE.equals(request.getEnabled()) && encryptedSecret == null) {
            throw invalid("启用统一用户前请配置 OIDC Client Secret");
        }

        UnifiedAuthConfig target = current == null ? new UnifiedAuthConfig() : current;
        if (current == null) {
            target.setConfigKey(CONFIG_KEY);
            target.setCreateTime(new Date());
            target.setConfigVersion(1L);
        } else {
            target.setConfigVersion(target.getConfigVersion() == null ? 1L : target.getConfigVersion() + 1L);
        }
        target.setIssuer(issuer);
        target.setClientId(clientId);
        target.setRedirectUri(StringUtils.defaultString(redirectUri));
        target.setClientSecretEnc(encryptedSecret);
        target.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        target.setAutoProvisionGeneralUser(Boolean.TRUE.equals(request.getAutoProvisionGeneralUser()));
        target.setTicketEnabled(ticketEnabled);
        target.setTicketValidationEndpoint(StringUtils.trimToNull(request.getTicketValidationEndpoint()));
        target.setTicketValidationMethod(normalizeTicketMethod(request.getTicketValidationMethod()));
        target.setTicketLoginEntryUrl(ticketLoginEntryUrl);
        target.setTicketIssuer(ticketEnabled ? validateTicketIssuer(request.getTicketIssuer())
                : StringUtils.trimToNull(request.getTicketIssuer()));
        target.setTicketParameterName(normalizeTicketParameterName(request.getTicketParameterName()));
        target.setTicketSuccessField(StringUtils.trimToNull(request.getTicketSuccessField()));
        target.setTicketSuccessValue(StringUtils.trimToNull(request.getTicketSuccessValue()));
        target.setTicketUsernameField(StringUtils.trimToNull(request.getTicketUsernameField()));
        target.setTicketResultIssuerPointer(defaultPointer(request.getTicketResultIssuerPointer(), "/issuer"));
        target.setTicketResultSubjectPointer(defaultPointer(request.getTicketResultSubjectPointer(), "/sub"));
        target.setTicketResultEmailPointer(defaultPointer(request.getTicketResultEmailPointer(), "/email"));
        target.setTicketResultDisplayNamePointer(defaultPointer(
                request.getTicketResultDisplayNamePointer(), "/name"));
        target.setUpdateTime(new Date());
        if (current == null) {
            configMapper.insert(target);
        } else {
            configMapper.updateById(target);
            // MyBatis-Plus skips nulls in updateById; write nullable ticket fields explicitly
            // so an administrator can clear stale provider settings from the UI.
            configMapper.update(null, new LambdaUpdateWrapper<UnifiedAuthConfig>()
                    .set(UnifiedAuthConfig::getTicketValidationEndpoint, target.getTicketValidationEndpoint())
                    .set(UnifiedAuthConfig::getTicketLoginEntryUrl, target.getTicketLoginEntryUrl())
                    .set(UnifiedAuthConfig::getTicketIssuer, target.getTicketIssuer())
                    .set(UnifiedAuthConfig::getTicketSuccessField, target.getTicketSuccessField())
                    .set(UnifiedAuthConfig::getTicketSuccessValue, target.getTicketSuccessValue())
                    .set(UnifiedAuthConfig::getTicketUsernameField, target.getTicketUsernameField())
                    .eq(UnifiedAuthConfig::getId, target.getId()));
        }
        String issuerHost = StringUtils.isBlank(issuer) ? "unconfigured" : URI.create(issuer).getHost();
        log.info("Unified-user auth config saved: issuerHost={}, oidcEnabled={}, ticketEnabled={}, autoProvision={}",
                issuerHost, target.getEnabled(), target.getTicketEnabled(), target.getAutoProvisionGeneralUser());
        return toConfigVO(target);
    }

    public UnifiedAuthConnectTestVO testConnection(UnifiedAuthConfigDTO request) {
        try {
            if (request == null || StringUtils.isBlank(request.getIssuer())
                    || StringUtils.isBlank(request.getClientId())) {
                return new UnifiedAuthConnectTestVO(false, "OIDC Provider 测试需要 Issuer 和 Client ID");
            }
            String issuer = normalizeIssuer(request == null ? null : request.getIssuer());
            ProviderMetadata metadata = discoverAndValidate(issuer);
            JsonNode keys = getJson(metadata.jwksUri()).path("keys");
            boolean hasSigningKey = false;
            if (keys.isArray()) {
                for (JsonNode key : keys) {
                    if ("RSA".equals(key.path("kty").asText())
                            && (key.path("alg").isMissingNode() || "RS256".equals(key.path("alg").asText()))
                            && StringUtils.isNotBlank(key.path("kid").asText())) {
                        hasSigningKey = true;
                        break;
                    }
                }
            }
            if (!hasSigningKey) {
                return new UnifiedAuthConnectTestVO(false, "OIDC JWKS 中未找到可用的 RS256 签名密钥");
            }
            return new UnifiedAuthConnectTestVO(true, "OIDC discovery 与签名密钥可访问");
        } catch (RuntimeException error) {
            log.warn("Unified-user OIDC connection test failed: type={}", error.getClass().getSimpleName());
            return new UnifiedAuthConnectTestVO(false, "无法连接 OIDC Provider，请检查 Issuer 地址和网络");
        }
    }

    public UnifiedAuthProviderVO getProvider() {
        UnifiedAuthConfig config = currentConfig();
        UnifiedAuthProviderVO result = new UnifiedAuthProviderVO();
        result.setEnabled(isOidcEnabled(config));
        result.setDisplayName("统一用户");
        result.setAuthenticationMode(authenticationMode);
        String ticketEntry = null;
        if (isTicketLoginEnabled(config)) {
            String configuredEntry = StringUtils.trimToNull(config.getTicketLoginEntryUrl());
            if (configuredEntry != null) {
                try {
                    ticketEntry = validateTicketLoginEntryUrl(configuredEntry);
                } catch (IllegalArgumentException invalidEntry) {
                    log.warn("Configured external ticket login entry URL is invalid: type={}",
                            invalidEntry.getClass().getSimpleName());
                }
            }
        }
        result.setTicketLoginEnabled(ticketEntry != null);
        result.setTicketLoginEntryUrl(ticketEntry);
        return result;
    }

    public OidcLoginStart beginLogin() {
        return beginLogin(null);
    }

    /** Keeps one active OIDC authorization attempt for a browser binding. */
    public OidcLoginStart beginLogin(String existingBrowserBinding) {
        UnifiedAuthConfig config = requireEnabledConfig();
        if (callbackUrl == null) {
            throw invalid("OIDC 可信回调地址尚未配置");
        }
        ProviderMetadata metadata = discoverAndValidate(config.getIssuer());
        String state = randomToken(32);
        String nonce = randomToken(32);
        String verifier = randomToken(48);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        long configVersion = config.getConfigVersion() == null ? 1L : config.getConfigVersion();
        String browserBinding = registerLoginAttempt(
                state, nonce, verifier, existingBrowserBinding, configVersion, System.currentTimeMillis());
        String authorizationUrl = UriComponentsBuilder.fromUri(metadata.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
        return new OidcLoginStart(authorizationUrl, browserBinding, STATE_TTL_MS / 1000);
    }

    private String registerLoginAttempt(
            String state,
            String nonce,
            String verifier,
            String existingBrowserBinding,
            long configVersion,
            long createdAt) {
        String browserBinding;
        synchronized (loginAttemptLock) {
            pruneExpired();
            String existingHash = StringUtils.isBlank(existingBrowserBinding)
                    ? null : hashBrowserBinding(existingBrowserBinding);
            boolean hasExistingAttempt = existingHash != null && loginAttempts.values().stream()
                    .anyMatch(attempt -> existingHash.equals(attempt.browserBindingHash()));
            browserBinding = hasExistingAttempt ? existingBrowserBinding : randomToken(32);
            if (hasExistingAttempt) {
                loginAttempts.entrySet().removeIf(
                        entry -> existingHash.equals(entry.getValue().browserBindingHash()));
            }
            if (loginAttempts.size() >= MAX_PENDING_LOGINS) {
                throw invalid("OIDC 登录请求过多，请稍后重试");
            }
            loginAttempts.put(state, new LoginAttempt(
                    nonce, verifier, hashBrowserBinding(browserBinding), configVersion, createdAt));
        }
        return browserBinding;
    }

    public OidcCallbackResult completeLogin(String code, String state, String browserBinding) {
        String stage = "callback_validation";
        try {
            if (StringUtils.isBlank(code) || StringUtils.isBlank(state)) {
                throw invalid("OIDC callback 参数无效");
            }
            stage = "state_validation";
            LoginAttempt attempt = loginAttempts.get(state);
            if (attempt == null) {
                throw invalid("OIDC 登录状态已失效，请重新登录");
            }
            if (System.currentTimeMillis() - attempt.createdAt() > STATE_TTL_MS) {
                loginAttempts.remove(state, attempt);
                throw invalid("OIDC 登录状态已失效，请重新登录");
            }
            if (!matchesBrowserBinding(attempt, browserBinding)) {
                throw invalid("OIDC 登录状态与发起登录的浏览器不匹配");
            }
            if (!loginAttempts.remove(state, attempt)) {
                throw invalid("OIDC 登录状态已使用，请重新登录");
            }

            stage = "provider_configuration";
            UnifiedAuthConfig config = requireEnabledConfig();
            long version = config.getConfigVersion() == null ? 1L : config.getConfigVersion();
            if (version != attempt.configVersion()) {
                throw invalid("OIDC 配置已变更，请重新登录");
            }
            stage = "discovery";
            ProviderMetadata metadata = discoverAndValidate(config.getIssuer());
            stage = "client_secret_decryption";
            String clientSecret = secretCipher.decrypt(config.getClientSecretEnc());
            stage = "token_exchange";
            JsonNode token = exchangeCode(metadata, config, clientSecret, code, attempt.codeVerifier());
            stage = "id_token_validation";
            JWTClaimsSet claims = verifyIdToken(
                    token.path("id_token").asText(null), metadata, config, attempt.nonce());

            String subject = StringUtils.defaultString(claims.getSubject());
            if (StringUtils.isBlank(subject) || subject.length() > 255) {
                throw invalid("OIDC subject 无效");
            }
            String issuer = claims.getIssuer();
            stage = "identity_resolution";
            return resolveUserForIdentity(config, "OIDC", issuer, subject,
                    textOrNull(claims.getClaim("email")), textOrNull(claims.getClaim("name")));
        } catch (RuntimeException failure) {
            log.warn("Unified-user OIDC callback failed: stage={}, failureType={}",
                    stage, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    public void cancelLogin(String state, String browserBinding) {
        LoginAttempt attempt = StringUtils.isBlank(state) ? null : loginAttempts.get(state);
        if (attempt != null && matchesBrowserBinding(attempt, browserBinding)) {
            loginAttempts.remove(state, attempt);
        }
    }

    /** Validates a one-use ticket with the configured external provider before reading identity fields. */
    public OidcCallbackResult completeExternalTicket(String ticket) {
        if (isDevBypass()) {
            throw invalid("当前使用 DEV_BYPASS，外部 ticket 登录不可用");
        }
        if (StringUtils.isBlank(ticket) || ticket.length() > 4096) {
            throw invalid("外部 SSO ticket 无效");
        }
        UnifiedAuthConfig config = currentConfig();
        if (config == null || !Boolean.TRUE.equals(config.getTicketEnabled())
                || StringUtils.isBlank(config.getTicketValidationEndpoint())) {
            throw invalid("外部 SSO ticket 登录尚未启用");
        }
        TicketReservation reservation = reserveTicket(ticket);
        JsonNode validationResult;
        try {
            validationResult = validateExternalTicket(config, ticket);
        } catch (RuntimeException failure) {
            releaseTicketReservation(reservation);
            throw failure;
        }
        consumeTicketReservation(reservation);
        String issuer = validateTicketIssuer(config.getTicketIssuer());
        String subject = dotPathText(validationResult, config.getTicketUsernameField());
        if (StringUtils.isBlank(subject) || subject.length() > 255) {
            throw invalid("外部 SSO ticket 未返回有效 subject");
        }
        return resolveUserForIdentity(config, "JYNC_TICKET", issuer, subject,
                optionalPointerText(validationResult, config.getTicketResultEmailPointer()),
                optionalPointerText(validationResult, config.getTicketResultDisplayNamePointer()));
    }

    public TicketLoginStart prepareExternalTicketLogin(String ticket) {
        return prepareExternalTicketLogin(ticket, null);
    }

    /** Reuses an active binding so a new ticket replaces that browser's one pending confirmation. */
    public TicketLoginStart prepareExternalTicketLogin(String ticket, String existingBrowserBinding) {
        OidcCallbackResult result = completeExternalTicket(ticket);
        if (result.identityId() == null) {
            throw invalid("外部 SSO 身份无法进入待确认状态");
        }
        return stageTicketLogin(result, existingBrowserBinding);
    }

    private TicketLoginStart stageTicketLogin(OidcCallbackResult result, String existingBrowserBinding) {
        String confirmationToken = randomToken(32);
        String cancellationToken = randomToken(32);
        long expiresAt = System.currentTimeMillis() + TICKET_PENDING_TTL_MS;
        String browserBinding;
        synchronized (pendingTicketLoginLock) {
            long now = System.currentTimeMillis();
            prunePendingTicketLogins(now);
            String existingKey = StringUtils.isBlank(existingBrowserBinding)
                    ? null : hashBrowserBinding(existingBrowserBinding);
            PendingTicketLogin existing = existingKey == null ? null : pendingTicketLogins.get(existingKey);
            boolean replaceActive = existing != null && existing.expiresAt() > now;
            browserBinding = replaceActive ? existingBrowserBinding : randomToken(32);
            String browserKey = hashBrowserBinding(browserBinding);
            if (!replaceActive && pendingTicketLogins.size() >= MAX_PENDING_TICKET_LOGINS) {
                throw invalid("待确认的外部登录过多，请稍后重试");
            }
            pendingTicketLogins.put(browserKey, new PendingTicketLogin(
                    result.identityId(), result.user() == null ? null : result.user().getId(),
                    result.configVersion(), confirmationToken, cancellationToken, expiresAt));
        }
        return new TicketLoginStart(browserBinding, TICKET_PENDING_TTL_MS / 1000);
    }

    public TicketLoginPending getPendingTicketLogin(String browserBinding) {
        if (StringUtils.isBlank(browserBinding)) {
            return null;
        }
        String key = hashBrowserBinding(browserBinding);
        PendingTicketLogin pending = pendingTicketLogins.get(key);
        long now = System.currentTimeMillis();
        if (pending == null || pending.expiresAt() <= now) {
            if (pending != null) {
                pendingTicketLogins.remove(key, pending);
            }
            return null;
        }
        UnifiedAuthConfig config = currentConfig();
        if (!isTicketLoginEnabled(config) || versionOf(config) != pending.configVersion()) {
            pendingTicketLogins.remove(key, pending);
            return null;
        }
        UnifiedAuthIdentity identity = identityMapper.selectById(pending.identityId());
        if (identity == null) {
            pendingTicketLogins.remove(key, pending);
            return null;
        }
        User user = pending.localUserId() == null || !pending.localUserId().equals(identity.getUserId())
                ? null : userMapper.selectById(pending.localUserId());
        boolean confirmable = user != null && user.getState() == Flag.YES.ordinal() && user.getUserType() != null;
        return new TicketLoginPending(
                StringUtils.defaultIfBlank(identity.getDisplayName(), identity.getSubject()),
                identity.getEmail(),
                confirmable ? user.getUserName() : null,
                confirmable ? pending.confirmationToken() : null,
                pending.cancellationToken(),
                confirmable);
    }

    public User confirmExternalTicketLogin(String browserBinding, String confirmationToken) {
        PendingTicketLogin pending = consumePendingTicketLogin(browserBinding, confirmationToken);
        UnifiedAuthConfig config = currentConfig();
        if (!isTicketLoginEnabled(config) || versionOf(config) != pending.configVersion()) {
            throw invalid("外部登录配置已变更，请重新登录");
        }
        UnifiedAuthIdentity identity = identityMapper.selectById(pending.identityId());
        if (identity == null || pending.localUserId() == null
                || !pending.localUserId().equals(identity.getUserId())) {
            throw invalid("外部身份绑定已变更，请重新登录或联系管理员");
        }
        User user = userMapper.selectById(pending.localUserId());
        if (user == null || user.getState() != Flag.YES.ordinal() || user.getUserType() == null) {
            throw invalid("绑定的本地用户不可用");
        }
        return user;
    }

    public boolean cancelExternalTicketLogin(String browserBinding, String cancellationToken) {
        if (StringUtils.isBlank(browserBinding)) {
            return false;
        }
        String key = hashBrowserBinding(browserBinding);
        PendingTicketLogin pending = pendingTicketLogins.get(key);
        if (pending == null || pending.expiresAt() <= System.currentTimeMillis()) {
            if (pending != null) {
                pendingTicketLogins.remove(key, pending);
            }
            return false;
        }
        if (StringUtils.isBlank(cancellationToken) || !matchesCancellationToken(pending, cancellationToken)) {
            return false;
        }
        return pendingTicketLogins.remove(key, pending);
    }

    private PendingTicketLogin consumePendingTicketLogin(String browserBinding, String confirmationToken) {
        if (StringUtils.isBlank(browserBinding) || StringUtils.isBlank(confirmationToken)) {
            throw invalid("外部登录确认状态无效");
        }
        String key = hashBrowserBinding(browserBinding);
        PendingTicketLogin pending = pendingTicketLogins.get(key);
        if (pending == null || pending.expiresAt() <= System.currentTimeMillis()) {
            if (pending != null) {
                pendingTicketLogins.remove(key, pending);
            }
            throw invalid("外部登录确认已过期，请重新登录");
        }
        if (!matchesConfirmationToken(pending, confirmationToken)
                || !pendingTicketLogins.remove(key, pending)) {
            throw invalid("外部登录确认状态无效或已使用");
        }
        return pending;
    }

    private boolean matchesConfirmationToken(PendingTicketLogin pending, String confirmationToken) {
        return matchesPendingToken(pending.confirmationToken(), confirmationToken);
    }

    private boolean matchesCancellationToken(PendingTicketLogin pending, String cancellationToken) {
        return matchesPendingToken(pending.cancellationToken(), cancellationToken);
    }

    private boolean matchesPendingToken(String expectedToken, String suppliedToken) {
        String expected = hashBrowserBinding(expectedToken);
        String actual = hashBrowserBinding(suppliedToken);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private void prunePendingTicketLogins(long now) {
        pendingTicketLogins.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private boolean isTicketLoginEnabled(UnifiedAuthConfig config) {
        return !isDevBypass() && config != null && Boolean.TRUE.equals(config.getTicketEnabled())
                && StringUtils.isNotBlank(config.getTicketValidationEndpoint());
    }

    private long versionOf(UnifiedAuthConfig config) {
        return config.getConfigVersion() == null ? 1L : config.getConfigVersion();
    }

    private OidcCallbackResult resolveUserForIdentity(
            UnifiedAuthConfig config,
            String identitySource,
            String issuer,
            String subject,
            String email,
            String displayName) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                OidcCallbackResult result = new TransactionTemplate(transactionManager).execute(status ->
                        resolveUserForIdentityInTransaction(
                                config, identitySource, issuer, subject, email, displayName));
                if (result != null) {
                    return result;
                }
            } catch (DuplicateKeyException | IdentityBindingChangedException race) {
                if (attempt == 2) {
                    throw invalid("外部身份正在变更，请稍后重试");
                }
                log.info("Unified-user identity provisioning raced with another update; retrying: source={}",
                        identitySource);
            }
        }
        throw invalid("外部身份正在变更，请稍后重试");
    }

    private OidcCallbackResult resolveUserForIdentityInTransaction(
            UnifiedAuthConfig config,
            String identitySource,
            String issuer,
            String subject,
            String email,
            String displayName) {
        UnifiedAuthIdentity identity = findOrCreateIdentity(identitySource, issuer, subject, email, displayName);
        User user = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Integer localUserId = identity.getUserId();
            if (localUserId == null) {
                break;
            }
            user = userMapper.selectById(localUserId);
            if (user != null) {
                break;
            }
            if (clearUserBinding(identity)) {
                break;
            }
            UnifiedAuthIdentity latestIdentity = identityMapper.selectById(identity.getId());
            if (latestIdentity == null) {
                identity.setUserId(null);
                break;
            }
            log.info("Unified-user identity binding changed during stale-user cleanup: identityId={}, "
                            + "previousLocalUserId={}, latestLocalUserId={}",
                    identity.getId(), localUserId, latestIdentity.getUserId());
            identity = latestIdentity;
        }
        if (user == null && Boolean.TRUE.equals(config.getAutoProvisionGeneralUser())) {
            user = provisionGeneralUser(identity);
        }
        if (user == null) {
            log.info("Unified-user external identity is pending administrator binding: identityId={}", identity.getId());
            return new OidcCallbackResult(null, true, identity.getId(), versionOf(config));
        }
        if (user.getState() != Flag.YES.ordinal() || user.getUserType() == null) {
            throw invalid("绑定的本地用户不可用");
        }

        log.info("Unified-user external identity verified: identityId={}, localUserId={}",
                identity.getId(), user.getId());
        return new OidcCallbackResult(user, false, identity.getId(), versionOf(config));
    }

    public List<UnifiedAuthIdentityVO> listMappings() {
        List<UnifiedAuthIdentityVO> result = new ArrayList<>();
        for (UnifiedAuthIdentity identity : identityMapper.selectList(
                new LambdaQueryWrapper<UnifiedAuthIdentity>().orderByDesc(UnifiedAuthIdentity::getUpdateTime))) {
            result.add(toIdentityVO(identity));
        }
        return result;
    }

    public List<UnifiedAuthLocalUserVO> listLocalUsers() {
        List<UnifiedAuthLocalUserVO> result = new ArrayList<>();
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getState, Flag.YES.ordinal())
                .orderByAsc(User::getUserName));
        for (User user : users) {
            if (user.getUserType() == null) {
                continue;
            }
            UnifiedAuthLocalUserVO vo = new UnifiedAuthLocalUserVO();
            vo.setId(user.getId());
            vo.setUserName(user.getUserName());
            vo.setEmail(user.getEmail());
            vo.setUserType(user.getUserType());
            result.add(vo);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindMapping(UnifiedAuthMappingDTO request) {
        if (request == null || request.getIdentityId() == null || request.getLocalUserId() == null) {
            throw invalid("外部身份和本地用户均为必填项");
        }
        UnifiedAuthIdentity identity = identityMapper.selectById(request.getIdentityId());
        User user = userMapper.selectById(request.getLocalUserId());
        if (identity == null || user == null || user.getState() != Flag.YES.ordinal()
                || user.getUserType() == null) {
            throw invalid("外部身份或本地用户不可用");
        }
        identity.setUserId(user.getId());
        identity.setUpdateTime(new Date());
        identityMapper.updateById(identity);
        log.info("Unified-user identity bound: identityId={}, localUserId={}, role={}",
                identity.getId(), user.getId(), user.getUserType());
    }

    @Transactional(rollbackFor = Exception.class)
    public void unbindMapping(Long identityId) {
        UnifiedAuthIdentity identity = identityId == null ? null : identityMapper.selectById(identityId);
        if (identity == null) {
            throw invalid("外部身份映射不存在");
        }
        Integer previousUserId = identity.getUserId();
        if (previousUserId != null && !clearUserBinding(identity)) {
            log.info("Unified-user identity binding changed before unbind; preserving latest binding: "
                            + "identityId={}, previousLocalUserId={}",
                    identityId, previousUserId);
            return;
        }
        log.info("Unified-user identity unbound: identityId={}", identityId);
    }

    private boolean clearUserBinding(UnifiedAuthIdentity identity) {
        Integer previousUserId = identity.getUserId();
        if (previousUserId == null) {
            return true;
        }
        Date updateTime = new Date();
        // MyBatis-Plus updateById ignores null fields, so clear the nullable binding explicitly.
        int updated = identityMapper.update(null, new LambdaUpdateWrapper<UnifiedAuthIdentity>()
                .set(UnifiedAuthIdentity::getUserId, null)
                .set(UnifiedAuthIdentity::getUpdateTime, updateTime)
                .eq(UnifiedAuthIdentity::getId, identity.getId())
                .eq(UnifiedAuthIdentity::getUserId, previousUserId));
        if (updated == 0) {
            return false;
        }
        identity.setUserId(null);
        identity.setUpdateTime(updateTime);
        return true;
    }

    public String callbackRedirect(OidcCallbackResult result) {
        return result.pending()
                ? UriComponentsBuilder.fromPath(frontendPath)
                    .queryParam("oidc", "pending").build().encode().toUriString()
                : frontendPath;
    }

    public String callbackFailureRedirect() {
        return UriComponentsBuilder.fromPath(frontendPath)
                .queryParam("oidc", "failed").build().encode().toUriString();
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    private UnifiedAuthConfig currentConfig() {
        return configMapper.selectOne(new LambdaQueryWrapper<UnifiedAuthConfig>()
                .eq(UnifiedAuthConfig::getConfigKey, CONFIG_KEY));
    }

    private UnifiedAuthConfig requireEnabledConfig() {
        UnifiedAuthConfig config = currentConfig();
        if (!isOidcEnabled(config)) {
            throw invalid("统一用户 OIDC Provider 尚未启用");
        }
        return config;
    }

    private boolean isOidcEnabled(UnifiedAuthConfig config) {
        return !isDevBypass() && callbackUrl != null && config != null
                && Boolean.TRUE.equals(config.getEnabled())
                && callbackUrl.equals(config.getRedirectUri())
                && StringUtils.isNotBlank(config.getClientSecretEnc());
    }

    private UnifiedAuthConfigVO toConfigVO(UnifiedAuthConfig config) {
        UnifiedAuthConfigVO vo = new UnifiedAuthConfigVO();
        vo.setRedirectUri(callbackUrl != null ? callbackUrl : config == null
                ? "" : StringUtils.defaultString(config.getRedirectUri()));
        if (config != null) {
            vo.setIssuer(config.getIssuer());
            vo.setClientId(config.getClientId());
            vo.setClientSecretConfigured(StringUtils.isNotBlank(config.getClientSecretEnc()));
            vo.setEnabled(Boolean.TRUE.equals(config.getEnabled()));
            vo.setAutoProvisionGeneralUser(Boolean.TRUE.equals(config.getAutoProvisionGeneralUser()));
            vo.setTicketEnabled(Boolean.TRUE.equals(config.getTicketEnabled()));
            vo.setTicketValidationEndpoint(config.getTicketValidationEndpoint());
            vo.setTicketValidationMethod(config.getTicketValidationMethod());
            vo.setTicketLoginEntryUrl(config.getTicketLoginEntryUrl());
            vo.setTicketIssuer(config.getTicketIssuer());
            vo.setTicketParameterName(normalizeTicketParameterName(config.getTicketParameterName()));
            vo.setTicketSuccessField(config.getTicketSuccessField());
            vo.setTicketSuccessValue(config.getTicketSuccessValue());
            vo.setTicketUsernameField(config.getTicketUsernameField());
            vo.setTicketResultIssuerPointer(config.getTicketResultIssuerPointer());
            vo.setTicketResultSubjectPointer(config.getTicketResultSubjectPointer());
            vo.setTicketResultEmailPointer(config.getTicketResultEmailPointer());
            vo.setTicketResultDisplayNamePointer(config.getTicketResultDisplayNamePointer());
            vo.setConfigVersion(config.getConfigVersion());
        }
        return vo;
    }

    private UnifiedAuthIdentityVO toIdentityVO(UnifiedAuthIdentity identity) {
        UnifiedAuthIdentityVO vo = new UnifiedAuthIdentityVO();
        vo.setIdentityId(identity.getId());
        vo.setIdentitySource(identity.getIdentitySource());
        vo.setIssuer(identity.getIssuer());
        vo.setSubject(identity.getSubject());
        vo.setEmail(identity.getEmail());
        vo.setDisplayName(identity.getDisplayName());
        vo.setLocalUserId(identity.getUserId());
        vo.setLastObservedAt(identity.getUpdateTime());
        if (identity.getUserId() != null) {
            User user = userMapper.selectById(identity.getUserId());
            if (user != null) {
                vo.setLocalUserName(user.getUserName());
                vo.setLocalUserType(user.getUserType());
            }
        }
        return vo;
    }

    private void validateConfigRequest(UnifiedAuthConfigDTO request) {
        if (request == null) {
            throw invalid("统一认证配置不能为空");
        }
        boolean oidcEnabled = Boolean.TRUE.equals(request.getEnabled());
        String issuer = StringUtils.trimToNull(request.getIssuer());
        String clientId = StringUtils.trimToNull(request.getClientId());
        if (oidcEnabled && (issuer == null || clientId == null)) {
            throw invalid("启用 OIDC 时 Issuer 和 Client ID 为必填项");
        }
        if (clientId != null && clientId.length() > 255) {
            throw invalid("Client ID 超过长度限制");
        }
        if (issuer != null) {
            normalizeIssuer(issuer);
        }
    }

    private void validateTicketConfig(UnifiedAuthConfigDTO request) {
        if (StringUtils.isBlank(request.getTicketValidationEndpoint())) {
            throw invalid("请填写外部 ticket 验证地址");
        }
        safeEndpoint(request.getTicketValidationEndpoint().trim());
        normalizeTicketMethod(request.getTicketValidationMethod());
        validateTicketIssuer(request.getTicketIssuer());
        normalizeTicketParameterName(request.getTicketParameterName());
        validateDotPath(request.getTicketSuccessField(), "ticket success field");
        if (StringUtils.isBlank(request.getTicketSuccessValue())) {
            throw invalid("请填写 ticket 验证成功标记值");
        }
        validateDotPath(request.getTicketUsernameField(), "ticket username field");
        validateJsonPointer(defaultPointer(request.getTicketResultEmailPointer(), "/email"));
        validateJsonPointer(defaultPointer(request.getTicketResultDisplayNamePointer(), "/name"));
    }

    private String validateTicketLoginEntryUrl(String value) {
        String normalized = safeEndpoint(value.trim()).toString();
        if (normalized.length() > 1024) {
            throw invalid("外部 ticket 登录入口地址超过长度限制");
        }
        return normalized;
    }

    private void validateTicketEndpointParameter(UnifiedAuthConfigDTO request) {
        URI endpoint = safeEndpoint(request.getTicketValidationEndpoint());
        String parameterName = normalizeTicketParameterName(request.getTicketParameterName());
        if (hasQueryParameter(endpoint.getRawQuery(), parameterName)) {
            throw invalid("外部 ticket 验证端点不能预先包含验票参数");
        }
    }

    private JsonNode validateExternalTicket(UnifiedAuthConfig config, String ticket) {
        URI endpoint = safeEndpoint(config.getTicketValidationEndpoint());
        String method = normalizeTicketMethod(config.getTicketValidationMethod());
        String parameterName = normalizeTicketParameterName(config.getTicketParameterName());
        String rawQuery = endpoint.getRawQuery();
        if (hasQueryParameter(rawQuery, parameterName)) {
            throw invalid("外部 ticket 验证端点已包含验票参数");
        }
        HttpRequest request;
        if ("GET".equals(method)) {
            String separator = rawQuery == null ? "?" : rawQuery.isEmpty() ? "" : "&";
            URI uri = URI.create(endpoint + separator + form(parameterName) + "=" + form(ticket));
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET().build();
        } else {
            String body = form(parameterName) + "=" + form(ticket);
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        }
        JsonNode result = sendTicketJson(request);
        String successMarker = dotPathText(result, config.getTicketSuccessField());
        if (!matchesConfiguredValues(successMarker, config.getTicketSuccessValue())) {
            throw invalid("外部 ticket 验证失败");
        }
        return result;
    }

    private boolean hasQueryParameter(String rawQuery, String parameterName) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return false;
        }
        try {
            for (String pair : rawQuery.split("&", -1)) {
                String rawName = pair.split("=", 2)[0];
                if (parameterName.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException error) {
            throw invalid("外部 ticket 验证端点查询参数无效");
        }
    }

    private JsonNode sendTicketJson(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = sendBoundedResponse(request);
            if (response.statusCode() != 200) {
                throw invalid("外部 ticket 验证端点拒绝了请求");
            }
            JsonNode json = MAPPER.readTree(response.body());
            if (json == null || !json.isObject()) {
                throw invalid("外部 ticket 验证响应格式无效");
            }
            return json;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw invalid("外部 ticket 验证请求中断");
        } catch (IOException error) {
            if (error instanceof ResponseTooLargeException) {
                throw invalid("外部 ticket 验证响应超出大小限制");
            }
            throw invalid("无法连接外部 ticket 验证端点");
        }
    }

    private String dotPathText(JsonNode root, String path) {
        validateDotPath(path, "ticket response field");
        JsonNode value = root;
        for (String segment : path.trim().split("\\.")) {
            if (value == null || !value.isObject()) {
                return null;
            }
            value = value.get(segment);
        }
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        return StringUtils.trimToNull(value.asText());
    }

    private void validateDotPath(String path, String label) {
        String value = StringUtils.trimToNull(path);
        if (value == null || value.length() > 128 || value.startsWith(".") || value.endsWith(".")
                || value.contains("..") || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("请填写有效的 " + label + "（例如 data.username）");
        }
    }

    private boolean matchesConfiguredValues(String actual, String commaSeparatedValues) {
        if (StringUtils.isBlank(actual) || StringUtils.isBlank(commaSeparatedValues)) {
            return false;
        }
        for (String candidate : commaSeparatedValues.split(",")) {
            if (candidate.trim().equals(actual.trim())) {
                return true;
            }
        }
        return false;
    }

    private String validateTicketIssuer(String issuer) {
        String value = StringUtils.trimToNull(issuer);
        if (value == null || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("请配置可信的 ticket Issuer 标识");
        }
        return value;
    }

    private String normalizeTicketParameterName(String parameterName) {
        String value = StringUtils.defaultIfBlank(parameterName, "ticket").trim();
        if (value.length() > 64 || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("ticket 参数名无效");
        }
        return value;
    }

    private String pointerText(JsonNode root, String pointer) {
        try {
            JsonNode value = root.at(JsonPointer.compile(defaultPointer(pointer, "")));
            if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
                throw invalid("外部 SSO ticket 返回字段缺失");
            }
            String text = value.asText();
            if (StringUtils.isBlank(text)) {
                throw invalid("外部 SSO ticket 返回字段为空");
            }
            return text.trim();
        } catch (IllegalArgumentException error) {
            throw invalid("外部 SSO ticket 字段映射无效");
        }
    }

    private String optionalPointerText(JsonNode root, String pointer) {
        try {
            JsonNode value = root.at(JsonPointer.compile(defaultPointer(pointer, "")));
            return value.isMissingNode() || value.isNull() || !value.isValueNode()
                    ? null : StringUtils.trimToNull(value.asText());
        } catch (IllegalArgumentException error) {
            throw invalid("外部 SSO ticket 字段映射无效");
        }
    }

    private void validateJsonPointer(String pointer) {
        if (pointer.length() > 256 || !pointer.startsWith("/")) {
            throw invalid("外部 SSO 返回字段请使用 JSON Pointer，例如 /user/sub");
        }
        try {
            JsonPointer.compile(pointer);
        } catch (IllegalArgumentException error) {
            throw invalid("外部 SSO 返回字段映射无效");
        }
    }

    private String defaultPointer(String pointer, String fallback) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(pointer), fallback);
    }

    private String normalizeTicketMethod(String method) {
        String normalized = StringUtils.defaultIfBlank(method, "GET").trim().toUpperCase(java.util.Locale.ROOT);
        if (!"GET".equals(normalized) && !"POST".equals(normalized)) {
            throw invalid("外部 ticket 验证方法仅支持 GET 或 POST");
        }
        return normalized;
    }

    private ProviderMetadata discoverAndValidate(String issuer) {
        JsonNode document = getJson(discoveryUri(issuer));
        requireExactIssuer(document.path("issuer").asText(), issuer,
                "OIDC discovery 返回的 Issuer 与配置不一致");
        URI authorization = safeEndpoint(document.path("authorization_endpoint").asText());
        URI token = safeEndpoint(document.path("token_endpoint").asText());
        URI jwks = safeEndpoint(document.path("jwks_uri").asText());
        return new ProviderMetadata(authorization, token, jwks);
    }

    private URI discoveryUri(String issuer) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        return safeEndpoint(base + "/.well-known/openid-configuration");
    }

    private JsonNode exchangeCode(
            ProviderMetadata metadata,
            UnifiedAuthConfig config,
            String clientSecret,
            String code,
            String codeVerifier) {
        String body = "grant_type=authorization_code"
                + "&code=" + form(code)
                + "&redirect_uri=" + form(callbackUrl)
                + "&code_verifier=" + form(codeVerifier);
        String credentials = form(config.getClientId()) + ":" + form(clientSecret);
        HttpRequest request = HttpRequest.newBuilder(metadata.tokenEndpoint())
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode tokenResponse = sendJson(request);
        if (StringUtils.isBlank(tokenResponse.path("id_token").asText())) {
            throw invalid("OIDC Provider 未返回 ID Token");
        }
        return tokenResponse;
    }

    private JWTClaimsSet verifyIdToken(
            String compactToken,
            ProviderMetadata metadata,
            UnifiedAuthConfig config,
            String expectedNonce) {
        if (StringUtils.isBlank(compactToken)) {
            throw invalid("OIDC ID Token 格式无效");
        }
        try {
            SignedJWT parsed = SignedJWT.parse(compactToken);
            if (!JWSAlgorithm.RS256.equals(parsed.getHeader().getAlgorithm())
                    || (parsed.getHeader().getCriticalParams() != null
                    && !parsed.getHeader().getCriticalParams().isEmpty())
                    || (parsed.getHeader().getType() != null
                    && !"JWT".equals(parsed.getHeader().getType().toString()))) {
                throw invalid("OIDC ID Token 使用了不支持的签名算法");
            }
            String kid = parsed.getHeader().getKeyID();
            RSAKey rsaKey = signingKey(getJson(metadata.jwksUri()), kid);
            if (rsaKey.getModulus() == null || rsaKey.getModulus().decodeToBigInteger().bitLength() < 2048) {
                throw invalid("OIDC RSA 签名密钥强度不足");
            }
            JWSVerifier verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
            if (!parsed.verify(verifier)) {
                throw invalid("OIDC ID Token 签名无效");
            }

            JWTClaimsSet claims = parsed.getJWTClaimsSet();
            requireExactIssuer(claims.getIssuer(), config.getIssuer(), "OIDC ID Token Issuer 不匹配");
            validateAudience(claims, config.getClientId());
            if (!expectedNonce.equals(stringClaim(claims, "nonce"))) {
                throw invalid("OIDC ID Token nonce 不匹配");
            }
            validateTokenTimes(claims, System.currentTimeMillis());
            if (StringUtils.isBlank(claims.getSubject())) {
                throw invalid("OIDC ID Token 缺少 subject");
            }
            return claims;
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw invalid("OIDC ID Token 验证失败");
        }
    }

    private RSAKey signingKey(JsonNode jwks, String kid) {
        if (StringUtils.isBlank(kid)) {
            throw invalid("OIDC ID Token 未提供签名密钥 ID");
        }
        try {
            JWK key = JWKSet.parse(jwks.toString()).getKeyByKeyId(kid);
            if (!(key instanceof RSAKey rsaKey)
                    || rsaKey.isPrivate()
                    || (rsaKey.getKeyUse() != null && !KeyUse.SIGNATURE.equals(rsaKey.getKeyUse()))
                    || (rsaKey.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(rsaKey.getAlgorithm()))) {
                throw invalid("OIDC JWKS 中不存在匹配的 RS256 签名密钥");
            }
            return rsaKey;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("OIDC JWKS 格式无效");
        }
    }

    static void validateTokenTimes(JWTClaimsSet claims, long now) {
        Date expiresAt = claims.getExpirationTime();
        Date notBefore = claims.getNotBeforeTime();
        Date issuedAt = claims.getIssueTime();
        if (issuedAt == null
                || expiresAt == null
                || expiresAt.getTime() + Duration.ofSeconds(CLOCK_SKEW_SECONDS).toMillis() < now
                || (notBefore != null && notBefore.getTime()
                - Duration.ofSeconds(CLOCK_SKEW_SECONDS).toMillis() > now)
                || issuedAt.getTime() - Duration.ofSeconds(CLOCK_SKEW_SECONDS).toMillis() > now) {
            throw new IllegalArgumentException("OIDC ID Token 已过期或尚未生效");
        }
    }

    static void validateAudience(JWTClaimsSet claims, String clientId) {
        List<String> audience = claims.getAudience();
        boolean matches = audience != null && audience.contains(clientId);
        boolean multiple = audience != null && audience.size() > 1;
        Object azpClaim = claims.getClaim("azp");
        String azp = azpClaim instanceof String text ? text : "";
        if (!matches
                || (azpClaim != null && !clientId.equals(azp))
                || (multiple && !clientId.equals(azp))) {
            throw invalid("OIDC ID Token Audience 不匹配");
        }
    }

    private UnifiedAuthIdentity findOrCreateIdentity(
            String identitySource, String issuer, String subject, String email, String displayName) {
        String key = identityKey(identitySource, issuer, subject);
        UnifiedAuthIdentity identity = identityMapper.selectOne(new LambdaQueryWrapper<UnifiedAuthIdentity>()
                .eq(UnifiedAuthIdentity::getIdentityKey, key));
        Date now = new Date();
        if (identity == null) {
            identity = new UnifiedAuthIdentity();
            identity.setIdentityKey(key);
            identity.setIdentitySource(identitySource);
            identity.setIssuer(issuer);
            identity.setSubject(subject);
            identity.setCreateTime(now);
        }
        if (email != null) {
            identity.setEmail(email);
        }
        if (displayName != null) {
            identity.setDisplayName(displayName);
        }
        identity.setUpdateTime(now);
        if (identity.getId() == null) {
            identityMapper.insert(identity);
        } else {
            identityMapper.update(null, new LambdaUpdateWrapper<UnifiedAuthIdentity>()
                    .set(email != null, UnifiedAuthIdentity::getEmail, email)
                    .set(displayName != null, UnifiedAuthIdentity::getDisplayName, displayName)
                    .set(UnifiedAuthIdentity::getUpdateTime, now)
                    .eq(UnifiedAuthIdentity::getId, identity.getId()));
        }
        return identity;
    }

    private User provisionGeneralUser(UnifiedAuthIdentity identity) {
        byte[] randomPassword = new byte[48];
        RANDOM.nextBytes(randomPassword);
        User user = new User();
        user.setUserName("sso_" + randomToken(12));
        user.setUserPassword(PASSWORD_ENCODER.encode(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomPassword)));
        user.setEmail(identity.getEmail());
        user.setUserType(UserType.GENERAL_USER);
        user.setState(Flag.YES.ordinal());
        user.setCreateTime(new Date());
        user.setUpdateTime(new Date());
        int inserted;
        try {
            inserted = userMapper.insert(user);
        } catch (RuntimeException failure) {
            log.warn("Unified-user OIDC provisioning failed: stage=local_user_insert, failureType={}",
                    failure.getClass().getSimpleName());
            throw failure;
        }
        if (inserted < 1 || user.getId() == null || user.getId() <= 0) {
            log.warn("Unified-user OIDC provisioning failed: stage=local_user_insert, "
                    + "failureType=MissingGeneratedUserId");
            throw invalid("本地用户记录未能生成有效 ID");
        }
        Date updateTime = new Date();
        int bound = identityMapper.update(null, new LambdaUpdateWrapper<UnifiedAuthIdentity>()
                .set(UnifiedAuthIdentity::getUserId, user.getId())
                .set(UnifiedAuthIdentity::getUpdateTime, updateTime)
                .eq(UnifiedAuthIdentity::getId, identity.getId())
                .isNull(UnifiedAuthIdentity::getUserId));
        if (bound == 0) {
            throw new IdentityBindingChangedException();
        }
        identity.setUserId(user.getId());
        identity.setUpdateTime(updateTime);
        log.info("Unified-user OIDC provisioned a GENERAL_USER account: identityId={}, localUserId={}",
                identity.getId(), user.getId());
        return user;
    }

    private JsonNode getJson(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendJson(request);
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = sendBoundedResponse(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw invalid("OIDC Provider 返回了无效响应");
            }
            return MAPPER.readTree(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw invalid("OIDC Provider 请求中断");
        } catch (IOException error) {
            if (error instanceof ResponseTooLargeException) {
                throw invalid("OIDC Provider 响应超出大小限制");
            }
            throw invalid("无法连接 OIDC Provider");
        }
    }

    HttpResponse<byte[]> sendBoundedResponse(HttpRequest request) throws IOException, InterruptedException {
        Duration timeout = request.timeout().orElse(Duration.ofSeconds(8));
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        AtomicReference<BoundedBodySubscriber> subscriberRef = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> responseFuture = HTTP.sendAsync(request, responseInfo -> {
            BoundedBodySubscriber subscriber = new BoundedBodySubscriber(MAX_RESPONSE_BYTES, deadlineNanos);
            subscriberRef.set(subscriber);
            return subscriber;
        });
        try {
            return responseFuture.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            responseFuture.cancel(true);
            BoundedBodySubscriber subscriber = subscriberRef.get();
            if (subscriber != null) {
                subscriber.cancel();
            }
            throw new java.net.http.HttpTimeoutException("HTTP response exceeded its total deadline");
        } catch (InterruptedException error) {
            responseFuture.cancel(true);
            BoundedBodySubscriber subscriber = subscriberRef.get();
            if (subscriber != null) {
                subscriber.cancel();
            }
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException) {
                responseFuture.cancel(true);
                BoundedBodySubscriber subscriber = subscriberRef.get();
                if (subscriber != null) {
                    subscriber.cancel();
                }
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("HTTP response failed", cause);
        } catch (CancellationException error) {
            throw new IOException("HTTP response was cancelled", error);
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maxBytes;
        private final long deadlineNanos;
        private Flow.Subscription subscription;
        private boolean cancelled;

        private BoundedBodySubscriber(int maxBytes, long deadlineNanos) {
            this.maxBytes = maxBytes;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (cancelled) {
                subscription.cancel();
            } else {
                subscription.request(1);
            }
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (cancelled || body.isDone()) {
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                fail(new java.net.http.HttpTimeoutException("HTTP body exceeded its total deadline"));
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int remaining = buffer.remaining();
                if (bytes.size() + remaining > maxBytes) {
                    fail(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[remaining];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
            if (subscription != null) {
                subscription.request(1);
            }
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (!body.isDone()) {
                body.completeExceptionally(error);
            }
        }

        @Override
        public synchronized void onComplete() {
            if (!cancelled && !body.isDone()) {
                body.complete(bytes.toByteArray());
            }
        }

        private synchronized void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            if (subscription != null) {
                subscription.cancel();
            }
            bytes.reset();
            body.completeExceptionally(new CancellationException("HTTP body subscription cancelled"));
        }

        private void fail(IOException error) {
            cancelled = true;
            if (subscription != null) {
                subscription.cancel();
            }
            bytes.reset();
            body.completeExceptionally(error);
        }
    }

    private static final class ResponseTooLargeException extends IOException {
    }

    private URI safeEndpoint(String value) {
        try {
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !("https".equalsIgnoreCase(scheme) || isLoopbackHttp(scheme, host))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException error) {
            throw invalid("OIDC Provider 地址必须使用 HTTPS（本机回环地址可使用 HTTP）");
        }
    }

    private String normalizeIssuer(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("OIDC Issuer 无效");
        }
        String issuer = value.trim();
        URI uri = safeEndpoint(issuer);
        if (uri.getQuery() != null) {
            throw invalid("OIDC Issuer 无效");
        }
        if (issuer.length() > 512) {
            throw invalid("OIDC Issuer 超过长度限制");
        }
        return issuer;
    }

    private void requireExactIssuer(String actual, String configured, String errorMessage) {
        if (!StringUtils.equals(configured, actual)) {
            throw invalid(errorMessage);
        }
    }

    private String validateRedirectUri(String value, String requiredPath) {
        URI uri = safeEndpoint(value);
        if (!requiredPath.equals(uri.getPath()) || uri.getQuery() != null) {
            throw new IllegalStateException("Unified-user callback URL must use the fixed callback path");
        }
        return uri.toString();
    }

    private String validateFrontendPath(String value) {
        String path = StringUtils.defaultIfBlank(value, "/bi").trim();
        URI uri;
        try {
            uri = URI.create(path);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Unified-user frontend path must be same-origin", error);
        }
        if (!path.startsWith("/") || path.startsWith("//") || uri.isAbsolute()
                || uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null
                || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Unified-user frontend path must be a same-origin path");
        }
        return path;
    }

    private boolean isLoopbackHttp(String scheme, String host) {
        if (!"http".equalsIgnoreCase(scheme)) {
            return false;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1")
                || normalized.equals("::1") || normalized.equals("[::1]");
    }

    private String identityKey(String identitySource, String issuer, String subject) {
        try {
            String material = "OIDC".equals(identitySource)
                    ? issuer + "\u0000" + subject
                    : identitySource + "\u0000" + issuer + "\u0000" + subject;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Identity key could not be calculated", error);
        }
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        loginAttempts.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > STATE_TTL_MS);
    }

    private String hashBrowserBinding(String browserBinding) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(browserBinding));
    }

    private boolean matchesBrowserBinding(LoginAttempt attempt, String browserBinding) {
        String expected = attempt.browserBindingHash();
        String actual = hashBrowserBinding(StringUtils.defaultString(browserBinding));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private TicketReservation reserveTicket(String ticket) {
        String hash;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
            hash = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Ticket replay key could not be calculated", error);
        }
        long now = System.currentTimeMillis();
        synchronized (ticketReplayLock) {
            pruneExpiredTicketReplayEntries(now);
            if (ticketReplayHashes.containsKey(hash)) {
                throw invalid("外部 SSO ticket 已使用或正在验证");
            }
            if (pendingTicketValidations >= MAX_PENDING_TICKET_VALIDATIONS) {
                throw invalid("外部 SSO ticket 验证请求过多，请稍后重试");
            }
            if (ticketReplayHashes.size() >= MAX_TICKET_REPLAY_HASHES) {
                throw invalid("外部 SSO ticket 验证繁忙，请稍后重试");
            }
            String reservationId = randomToken(24);
            ticketReplayHashes.put(hash, new TicketReplayEntry(
                    reservationId, TicketReplayState.PENDING, now + TICKET_PENDING_TTL_MS));
            pendingTicketValidations++;
            return new TicketReservation(hash, reservationId);
        }
    }

    private void consumeTicketReservation(TicketReservation reservation) {
        long now = System.currentTimeMillis();
        synchronized (ticketReplayLock) {
            pruneExpiredTicketReplayEntries(now);
            TicketReplayEntry current = ticketReplayHashes.get(reservation.hash());
            if (current != null && current.state() == TicketReplayState.CONSUMED) {
                throw invalid("外部 SSO ticket 已使用或正在验证");
            }
            if (current != null) {
                if (!current.reservationId().equals(reservation.reservationId())) {
                    throw invalid("外部 SSO ticket 验证请求已过期，请重新登录");
                }
                pendingTicketValidations--;
            } else if (ticketReplayHashes.size() >= MAX_TICKET_REPLAY_HASHES) {
                throw invalid("外部 SSO ticket 验证繁忙，请稍后重试");
            }
            ticketReplayHashes.put(reservation.hash(), new TicketReplayEntry(
                    reservation.reservationId(), TicketReplayState.CONSUMED, now + TICKET_REPLAY_TTL_MS));
        }
    }

    private void releaseTicketReservation(TicketReservation reservation) {
        synchronized (ticketReplayLock) {
            TicketReplayEntry current = ticketReplayHashes.get(reservation.hash());
            if (current != null && current.state() == TicketReplayState.PENDING
                    && current.reservationId().equals(reservation.reservationId())) {
                ticketReplayHashes.remove(reservation.hash());
                pendingTicketValidations--;
            }
        }
    }

    private void pruneExpiredTicketReplayEntries(long now) {
        ticketReplayHashes.entrySet().removeIf(entry -> {
            TicketReplayEntry replayEntry = entry.getValue();
            if (replayEntry.expiresAt() > now) {
                return false;
            }
            if (replayEntry.state() == TicketReplayState.PENDING) {
                pendingTicketValidations--;
            }
            return true;
        });
    }

    private enum TicketReplayState {
        PENDING,
        CONSUMED
    }

    private record TicketReplayEntry(String reservationId, TicketReplayState state, long expiresAt) {
    }

    private record TicketReservation(String hash, String reservationId) {
    }

    private boolean isDevBypass() {
        return "DEV_BYPASS".equalsIgnoreCase(authenticationMode);
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception error) {
            throw new IllegalStateException("PKCE challenge could not be calculated", error);
        }
    }

    private String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String textOrNull(Object value) {
        return value instanceof String text && StringUtils.isNotBlank(text) ? text.trim() : null;
    }

    private String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String text ? text : "";
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record ProviderMetadata(URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri) {
    }

    private record LoginAttempt(
            String nonce,
            String codeVerifier,
            String browserBindingHash,
            long configVersion,
            long createdAt) {
    }

    public record OidcLoginStart(String authorizationUrl, String browserBinding, long maxAgeSeconds) {
    }

    public record OidcCallbackResult(User user, boolean pending, Long identityId, long configVersion) {

        public OidcCallbackResult(User user, boolean pending) {
            this(user, pending, null, 1L);
        }
    }

    public record TicketLoginStart(String browserBinding, long maxAgeSeconds) {
    }

    public record TicketLoginPending(
            String externalDisplayName,
            String externalEmail,
            String localUserName,
            String confirmationToken,
            String cancellationToken,
            boolean confirmable) {
    }

    private record PendingTicketLogin(
            Long identityId,
            Integer localUserId,
            long configVersion,
            String confirmationToken,
            String cancellationToken,
            long expiresAt) {
    }

    private static final class IdentityBindingChangedException extends RuntimeException {
    }
}
