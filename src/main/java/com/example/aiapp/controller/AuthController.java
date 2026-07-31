package com.example.aiapp.controller;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.dto.ChangePasswordRequestDTO;
import com.example.aiapp.domain.dto.RegisterRequestDTO;
import com.example.aiapp.domain.vo.AuthSessionVO;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.security.AuthSessionService;
import com.example.aiapp.security.AuthSecurityEventService;
import com.example.aiapp.security.CurrentUser;
import com.example.aiapp.service.AuthService;
import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthSessionService authSessionService;
    private final AuthSecurityEventService securityEventService;
    private final AuthProperties authProperties;

    public AuthController(
            AuthService authService,
            AuthSessionService authSessionService,
            AuthSecurityEventService securityEventService,
            AuthProperties authProperties
    ) {
        this.authService = authService;
        this.authSessionService = authSessionService;
        this.securityEventService = securityEventService;
        this.authProperties = authProperties;
    }

    @PostMapping("/register")
    AuthResponseVO register(
            @Valid @RequestBody RegisterRequestDTO request,
            HttpServletRequest servletRequest,
            HttpServletResponse response
    ) {
        try {
            AuthService.AuthenticatedSession session = authService.register(
                    request.toAuthRequest(),
                    servletRequest.getHeader(HttpHeaders.USER_AGENT),
                    servletRequest.getRemoteAddr()
            );
            securityEventService.record(
                    "REGISTER", "SUCCEEDED", null, session.response().user().id(),
                    request.username(), servletRequest.getRemoteAddr()
            );
            setRefreshCookie(response, session.refreshToken());
            return session.response();
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            securityEventService.record(
                    "REGISTER", "FAILED", "REGISTRATION_REJECTED", null,
                    request.username(), servletRequest.getRemoteAddr()
            );
            throw exception;
        }
    }

    @PostMapping("/login")
    AuthResponseVO login(
            @Valid @RequestBody AuthRequestDTO request,
            HttpServletRequest servletRequest,
            HttpServletResponse response
    ) {
        try {
            AuthService.AuthenticatedSession session = authService.login(
                    request,
                    servletRequest.getHeader(HttpHeaders.USER_AGENT),
                    servletRequest.getRemoteAddr()
            );
            securityEventService.record(
                    "LOGIN", "SUCCEEDED", null, session.response().user().id(),
                    request.username(), servletRequest.getRemoteAddr()
            );
            setRefreshCookie(response, session.refreshToken());
            return session.response();
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            securityEventService.record(
                    "LOGIN", "FAILED", "CREDENTIALS_REJECTED", null,
                    request.username(), servletRequest.getRemoteAddr()
            );
            throw exception;
        }
    }

    @PostMapping("/refresh")
    AuthResponseVO refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        try {
            AuthService.AuthenticatedSession session = authService.refresh(readRefreshCookie(request));
            securityEventService.record(
                    "REFRESH", "SUCCEEDED", null, session.response().user().id(),
                    null, request.getRemoteAddr()
            );
            setRefreshCookie(response, session.refreshToken());
            return session.response();
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            securityEventService.record(
                    "REFRESH", "FAILED", "SESSION_REJECTED", null,
                    null, request.getRemoteAddr()
            );
            clearRefreshCookie(response);
            throw exception;
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletResponse response) {
        CurrentUser user = CurrentUserUtils.currentUser();
        authService.logout(user.id(), user.sessionId());
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(HttpServletResponse response) {
        CurrentUser user = CurrentUserUtils.currentUser();
        authService.logoutAll(user.id());
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    AuthResponseVO changePassword(@Valid @RequestBody ChangePasswordRequestDTO request) {
        CurrentUser user = CurrentUserUtils.currentUser();
        return authService.changePassword(user.id(), user.sessionId(), request);
    }

    @GetMapping("/session")
    AuthSessionVO session() {
        CurrentUser user = CurrentUserUtils.currentUser();
        AuthSessionService.SessionInfo session = authSessionService.currentSession(user.sessionId(), user.id());
        if (session == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return new AuthSessionVO(session.createdAt(), session.lastUsedAt(), session.expiresAt(), true);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("detail", "用户名或密码不正确，请重试。"));
    }

    private String readRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return "";
        }
        for (var cookie : request.getCookies()) {
            if (authProperties.refreshCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return "";
    }

    private void setRefreshCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(authProperties.refreshCookieName(), token)
                .httpOnly(true)
                .secure(authProperties.secureCookie())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(java.time.Duration.ofDays(authProperties.refreshTtlDays()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(authProperties.refreshCookieName(), "")
                .httpOnly(true)
                .secure(authProperties.secureCookie())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(java.time.Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
