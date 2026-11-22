package org.apache.seatunnel.web.api.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.SessionService;
import org.apache.seatunnel.web.api.service.impl.UnifiedAuthServiceImpl;
import org.apache.seatunnel.web.common.constants.Constants;
import org.apache.seatunnel.web.common.enums.UserType;
import org.apache.seatunnel.web.dao.entity.User;
import org.apache.seatunnel.web.spi.bean.dto.UnifiedAuthConfigDTO;
import org.apache.seatunnel.web.spi.bean.dto.UnifiedAuthMappingDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthConfigVO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthConnectTestVO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthIdentityVO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthLocalUserVO;
import org.apache.seatunnel.web.spi.bean.vo.UnifiedAuthProviderVO;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

/** Public OIDC callbacks and administrator-only unified-user operations. */
@RestController
@Slf4j
@RequestMapping("/api/v1/unified-auth")
public class UnifiedAuthController {

    private static final String OIDC_BINDING_COOKIE = "ST_OIDC_BROWSER_BINDING";
    private static final String TICKET_BINDING_COOKIE = "ST_TICKET_BROWSER_BINDING";
    private static final String REFERRER_POLICY_HEADER = "Referrer-Policy";

    @Value("${security.authentication.secure-cookies:true}")
    private boolean secureCookies = true;

    @Resource
    private UnifiedAuthServiceImpl unifiedAuthService;
    @Resource
    private CurrentUserProvider currentUserProvider;
    @Resource
    private SessionService sessionService;

    @GetMapping("/config")
    public Result<UnifiedAuthConfigVO> getConfig() {
        requireAdmin();
        return Result.buildSuc(unifiedAuthService.getConfig());
    }

    @PutMapping("/config")
    public Result<UnifiedAuthConfigVO> saveConfig(@RequestBody UnifiedAuthConfigDTO request) {
        requireAdmin();
        return Result.buildSuc(unifiedAuthService.saveConfig(request));
    }

    @PostMapping("/connect-test")
    public Result<UnifiedAuthConnectTestVO> testConnection(@RequestBody UnifiedAuthConfigDTO request) {
        requireAdmin();
        return Result.buildSuc(unifiedAuthService.testConnection(request));
    }

    @GetMapping("/mappings")
    public Result<List<UnifiedAuthIdentityVO>> mappings() {
        requireAdmin();
        return Result.buildSuc(unifiedAuthService.listMappings());
    }

    @GetMapping("/local-users")
    public Result<List<UnifiedAuthLocalUserVO>> localUsers() {
        requireAdmin();
        return Result.buildSuc(unifiedAuthService.listLocalUsers());
    }

    @PostMapping("/mappings")
    public Result<Void> bindMapping(@RequestBody UnifiedAuthMappingDTO request) {
        requireAdmin();
        unifiedAuthService.bindMapping(request);
        return Result.buildSuc();
    }

    @DeleteMapping("/mappings/{identityId}")
    public Result<Void> unbindMapping(@PathVariable("identityId") Long identityId) {
        requireAdmin();
        unifiedAuthService.unbindMapping(identityId);
        return Result.buildSuc();
    }

    @GetMapping("/oidc/provider")
    public Result<UnifiedAuthProviderVO> provider() {
        return Result.buildSuc(unifiedAuthService.getProvider());
    }

    @GetMapping("/oidc/start")
    public ResponseEntity<Void> startLogin(HttpServletRequest request) {
        UnifiedAuthServiceImpl.OidcLoginStart start = unifiedAuthService.beginLogin(browserBinding(request));
        ResponseCookie bindingCookie = ResponseCookie.from(OIDC_BINDING_COOKIE, start.browserBinding())
                .httpOnly(true)
                .secure(secureCookie(request))
                .path("/api/v1/unified-auth/oidc/callback/seatunnel")
                .sameSite("Lax")
                .maxAge(start.maxAgeSeconds())
                .build();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(start.authorizationUrl()))
                .header(HttpHeaders.SET_COOKIE, bindingCookie.toString())
                .build();
    }

    @GetMapping("/oidc/callback/seatunnel")
    public ResponseEntity<Void> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response) {
        String stage = "callback_validation";
        try {
            if (StringUtils.isNotBlank(error)) {
                unifiedAuthService.cancelLogin(state, browserBinding(request));
                return redirect(unifiedAuthService.callbackFailureRedirect()).build();
            }
            stage = "oidc_service";
            UnifiedAuthServiceImpl.OidcCallbackResult result =
                    unifiedAuthService.completeLogin(code, state, browserBinding(request));
            if (!result.pending()) {
                stage = "session_creation";
                createSessionCookie(result.user(), request, response);
            }
            stage = "redirect";
            return redirect(unifiedAuthService.callbackRedirect(result)).build();
        } catch (RuntimeException failure) {
            log.warn("Unified-user OIDC callback failed: stage={}, failureType={}",
                    stage, failure.getClass().getSimpleName());
            return redirect(unifiedAuthService.callbackFailureRedirect()).build();
        }
    }

    /** Validates a one-use ticket server-side and stages a browser-bound confirmation. */
    @GetMapping("/sso/entry")
    public ResponseEntity<Void> enterExternalTicketSession(HttpServletRequest servletRequest) {
        try {
            String[] tickets = servletRequest.getParameterValues("ticket");
            if (tickets == null || tickets.length != 1) {
                return redirect("/login?ticketLogin=failed").build();
            }
            UnifiedAuthServiceImpl.TicketLoginStart start =
                    unifiedAuthService.prepareExternalTicketLogin(
                            tickets[0], cookieValue(servletRequest, TICKET_BINDING_COOKIE));
            ResponseCookie bindingCookie = ResponseCookie.from(TICKET_BINDING_COOKIE, start.browserBinding())
                    .httpOnly(true)
                    .secure(secureCookie(servletRequest))
                    .path("/api/v1/unified-auth/sso")
                    .sameSite("Lax")
                    .maxAge(start.maxAgeSeconds())
                    .build();
            return redirect("/login")
                    .header(HttpHeaders.SET_COOKIE, bindingCookie.toString())
                    .build();
        } catch (RuntimeException failure) {
            return redirect("/login?ticketLogin=failed").build();
        }
    }

    @GetMapping("/sso/pending")
    public ResponseEntity<Result<UnifiedAuthServiceImpl.TicketLoginPending>> pendingExternalTicketLogin(
            HttpServletRequest request) {
        requireSameOriginBrowser(request);
        UnifiedAuthServiceImpl.TicketLoginPending pending = unifiedAuthService.getPendingTicketLogin(
                cookieValue(request, TICKET_BINDING_COOKIE));
        if (pending == null) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .cacheControl(CacheControl.noStore())
                    .header(REFERRER_POLICY_HEADER, "no-referrer")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY_HEADER, "no-referrer")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(Result.buildSuc(pending));
    }

    @PostMapping("/sso/confirm")
    public ResponseEntity<Result<Void>> confirmExternalTicketLogin(
            @RequestBody TicketConfirmationRequest confirmation,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireSameOriginBrowser(request);
        User user = unifiedAuthService.confirmExternalTicketLogin(
                cookieValue(request, TICKET_BINDING_COOKIE),
                confirmation == null ? null : confirmation.confirmationToken());
        createSessionCookie(user, request, response);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY_HEADER, "no-referrer")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(Result.buildSuc());
    }

    @PostMapping("/sso/cancel")
    public ResponseEntity<Result<Void>> cancelExternalTicketLogin(
            @RequestBody TicketCancellationRequest cancellation,
            HttpServletRequest request) {
        requireSameOriginBrowser(request);
        boolean cancelled = unifiedAuthService.cancelExternalTicketLogin(
                cookieValue(request, TICKET_BINDING_COOKIE),
                cancellation == null ? null : cancellation.cancellationToken());
        if (!cancelled) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .header(REFERRER_POLICY_HEADER, "no-referrer")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(Result.buildFailure("外部登录状态已变化，请刷新登录页面"));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY_HEADER, "no-referrer")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(Result.buildSuc());
    }

    private void createSessionCookie(
            User user, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String sessionId = sessionService.createSession(user, servletRequest.getRemoteAddr());
        if (StringUtils.isBlank(sessionId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SSO session could not be created");
        }
        ResponseCookie cookie = ResponseCookie.from(Constants.SESSION_ID, sessionId)
                .httpOnly(true)
                .secure(secureCookie(servletRequest))
                .path("/")
                .sameSite("Lax")
                .build();
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void requireAdmin() {
        User user = currentUserProvider.getCurrentUser();
        if (user == null || user.getUserType() != UserType.ADMIN_USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员权限不足");
        }
    }

    private ResponseEntity.BodyBuilder redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY_HEADER, "no-referrer")
                .header(HttpHeaders.PRAGMA, "no-cache");
    }

    private void requireSameOriginBrowser(HttpServletRequest request) {
        if (!"same-origin".equalsIgnoreCase(StringUtils.trimToEmpty(request.getHeader("Sec-Fetch-Site")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求必须来自同源浏览器页面");
        }
    }

    private String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String browserBinding(HttpServletRequest request) {
        return cookieValue(request, OIDC_BINDING_COOKIE);
    }

    private boolean secureCookie(HttpServletRequest request) {
        return secureCookies || request.isSecure();
    }

    public record TicketConfirmationRequest(String confirmationToken) {
    }

    public record TicketCancellationRequest(String cancellationToken) {
    }

}
