package org.apache.seatunnel.web.api.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/unified-auth")
public class UnifiedAuthController {

    private static final String OIDC_BINDING_COOKIE = "ST_OIDC_BROWSER_BINDING";

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
        UnifiedAuthServiceImpl.OidcLoginStart start = unifiedAuthService.beginLogin();
        ResponseCookie bindingCookie = ResponseCookie.from(OIDC_BINDING_COOKIE, start.browserBinding())
                .httpOnly(true)
                .secure(request.isSecure())
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
        try {
            if (StringUtils.isNotBlank(error)) {
                unifiedAuthService.cancelLogin(state, browserBinding(request));
                return redirect(unifiedAuthService.callbackFailureRedirect());
            }
            UnifiedAuthServiceImpl.OidcCallbackResult result =
                    unifiedAuthService.completeLogin(code, state, browserBinding(request));
            if (!result.pending()) {
                createSessionCookie(result.user(), request, response);
            }
            return redirect(unifiedAuthService.callbackRedirect(result));
        } catch (RuntimeException failure) {
            return redirect(unifiedAuthService.callbackFailureRedirect());
        } finally {
            clearBrowserBindingCookie(request, response);
        }
    }

    /** Validates a one-use external ticket server-side and sets the normal Web session cookie. */
    @GetMapping("/sso/entry")
    public ResponseEntity<Void> enterExternalTicketSession(
            @RequestParam(value = "ticket", required = false) String ticket,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            UnifiedAuthServiceImpl.OidcCallbackResult result =
                    unifiedAuthService.completeExternalTicket(ticket);
            if (!result.pending()) {
                createSessionCookie(result.user(), servletRequest, servletResponse);
            }
            return redirect(unifiedAuthService.callbackRedirect(result));
        } catch (RuntimeException failure) {
            return redirect(unifiedAuthService.callbackFailureRedirect());
        }
    }

    private void createSessionCookie(
            User user, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String sessionId = sessionService.createSession(user, servletRequest.getRemoteAddr());
        if (StringUtils.isBlank(sessionId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SSO session could not be created");
        }
        ResponseCookie cookie = ResponseCookie.from(Constants.SESSION_ID, sessionId)
                .httpOnly(true)
                .secure(servletRequest.isSecure())
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

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private String browserBinding(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (OIDC_BINDING_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void clearBrowserBindingCookie(
            HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(OIDC_BINDING_COOKIE, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .path("/api/v1/unified-auth/oidc/callback/seatunnel")
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

}
