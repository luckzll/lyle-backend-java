package com.example.aiapp.controller;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.security.AuthSecurityEventService;
import com.example.aiapp.security.HumanVerificationService;
import com.example.aiapp.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Password access for existing administrators; this route never registers or promotes users. */
@RestController
@RequestMapping("/auth/admin/password")
public class AdminPasswordController {
    private static final String CREDENTIAL_ERROR = "用户名或密码不正确，请重试。";
    private final AuthService auth;
    private final HumanVerificationService verification;
    private final AuthProperties properties;
    private final AuthSecurityEventService events;

    public AdminPasswordController(AuthService auth, HumanVerificationService verification,
            AuthProperties properties, AuthSecurityEventService events) {
        this.auth = auth; this.verification = verification; this.properties = properties; this.events = events;
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password,
            @Size(max = 2048) String captchaToken, @Size(max = 256) String captchaRandstr) {}

    @PostMapping("/login")
    public AuthResponseVO login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            // Consume the proof before every credential check, even when a password is rejected.
            verification.verify(body.captchaToken(), body.captchaRandstr(), request);
        } catch (ResponseStatusException exception) {
            throw new LoginHumanVerificationException(exception);
        }
        try {
            if (body.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, CREDENTIAL_ERROR);
            }
            var session = auth.loginAdministrator(new AuthRequestDTO(body.username().trim(), body.password()),
                    request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
            events.record("ADMIN_PASSWORD_LOGIN", "SUCCEEDED", null, session.response().user().id(), null, request.getRemoteAddr());
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(properties.refreshCookieName(), session.refreshToken())
                    .httpOnly(true).secure(properties.secureCookie()).sameSite("Lax").path("/api/auth")
                    .maxAge(Duration.ofDays(properties.refreshTtlDays())).build().toString());
            return session.response();
        } catch (ResponseStatusException exception) {
            events.record("ADMIN_PASSWORD_LOGIN", "FAILED", "CREDENTIALS_REJECTED", null, null, request.getRemoteAddr());
            throw exception;
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> rejected(ResponseStatusException exception) {
        String detail = exception instanceof LoginHumanVerificationException
                ? exception.getReason() : CREDENTIAL_ERROR;
        return ResponseEntity.status(exception.getStatusCode()).headers(exception.getHeaders())
                .body(Map.of("detail", detail == null ? "安全验证未通过，请重新验证。" : detail));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException exception) {
        boolean invalidProof = exception.getBindingResult().getFieldErrors().stream()
                .anyMatch(error -> error.getField().equals("captchaToken") || error.getField().equals("captchaRandstr"));
        return ResponseEntity.badRequest().body(Map.of("detail", invalidProof
                ? "安全验证未通过、已使用或已过期，请重新验证。" : CREDENTIAL_ERROR));
    }

    private static final class LoginHumanVerificationException extends ResponseStatusException {
        private final ResponseStatusException original;
        private LoginHumanVerificationException(ResponseStatusException original) {
            super(original.getStatusCode(), original.getReason(), original);
            this.original = original;
        }
        @Override public HttpHeaders getHeaders() { return original.getHeaders(); }
    }
}
