package com.example.aiapp.controller;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.security.AuthSecurityEventService;
import com.example.aiapp.security.EmailCodeService;
import com.example.aiapp.security.HumanVerificationService;
import com.example.aiapp.service.EmailIdentityService;
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

@RestController
@RequestMapping("/auth/email/password")
public class EmailPasswordController {
    private final EmailCodeService codes;
    private final EmailIdentityService identities;
    private final HumanVerificationService verification;
    private final AuthProperties properties;
    private final AuthSecurityEventService events;

    public EmailPasswordController(EmailCodeService codes, EmailIdentityService identities,
            HumanVerificationService verification, AuthProperties properties, AuthSecurityEventService events) {
        this.codes = codes; this.identities = identities; this.verification = verification;
        this.properties = properties; this.events = events;
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password,
            @Size(max = 2048) String captchaToken, @Size(max = 256) String captchaRandstr) {}
    public record ResetRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(min = 6, max = 6) String code,
            @NotBlank @Size(min = 8, max = 64, message = "密码需要 8～64 个字符。") String password) {}

    @PostMapping("/login")
    public AuthResponseVO login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        try {
            // A proof is consumed for every password attempt, including incorrect credentials.
            verification.verify(body.captchaToken(), body.captchaRandstr(), request);
        } catch (ResponseStatusException exception) {
            throw new LoginHumanVerificationException(exception);
        }
        try {
            if (body.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "邮箱或密码不正确，请重试。");
            }
            var session = identities.loginWithPassword(EmailCodeService.normalize(body.email()), body.password(),
                    request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
            events.record("EMAIL_PASSWORD_LOGIN", "SUCCEEDED", null, session.response().user().id(), null, request.getRemoteAddr());
            setCookie(response, session.refreshToken());
            return session.response();
        } catch (ResponseStatusException exception) {
            events.record("EMAIL_PASSWORD_LOGIN", "FAILED", "CREDENTIALS_REJECTED", null, null, request.getRemoteAddr());
            throw exception;
        }
    }

    @PostMapping("/code")
    public Map<String, Object> code(@Valid @RequestBody EmailAuthController.EmailRequest body, HttpServletRequest request) {
        EmailCodeService.normalize(body.email());
        verification.verify(body.captchaToken(), body.captchaRandstr(), request);
        // A login or binding code cannot be replayed to set a password.
        codes.send(body.email(), "password");
        return Map.of("message", "验证码已发送。", "retryAfterSeconds", 60);
    }

    @PostMapping("/reset")
    public AuthResponseVO reset(@Valid @RequestBody ResetRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (body.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "密码过长，请减少字符后重试。");
        }
        String email = codes.verify(body.email(), body.code(), "password");
        var session = identities.resetPassword(email, body.password(), request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
        events.record("EMAIL_PASSWORD_RESET", "SUCCEEDED", null, session.response().user().id(), null, request.getRemoteAddr());
        setCookie(response, session.refreshToken());
        return session.response();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> rejected(ResponseStatusException exception, HttpServletRequest request) {
        String detail = request.getRequestURI().endsWith("/login") && !(exception instanceof LoginHumanVerificationException)
                ? "邮箱或密码不正确，请重试。"
                : exception.getReason() == null ? "请求未能完成，请稍后重试。" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).headers(exception.getHeaders()).body(Map.of("detail", detail));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String detail = request.getRequestURI().endsWith("/login") ? "邮箱或密码不正确，请重试。"
                : "请检查邮箱、6 位验证码和密码（8～64 个字符）。";
        if (request.getRequestURI().endsWith("/login") && exception.getBindingResult().getFieldErrors().stream()
                .anyMatch(error -> error.getField().equals("captchaToken") || error.getField().equals("captchaRandstr"))) {
            detail = "安全验证未通过、已使用或已过期，请重新验证。";
        }
        return ResponseEntity.badRequest().body(Map.of("detail", detail));
    }

    private static final class LoginHumanVerificationException extends ResponseStatusException {
        private final ResponseStatusException original;

        private LoginHumanVerificationException(ResponseStatusException original) {
            super(original.getStatusCode(), original.getReason(), original);
            this.original = original;
        }

        @Override
        public HttpHeaders getHeaders() {
            return original.getHeaders();
        }
    }

    private void setCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(properties.refreshCookieName(), token)
                .httpOnly(true).secure(properties.secureCookie()).sameSite("Lax").path("/api/auth")
                .maxAge(Duration.ofDays(properties.refreshTtlDays())).build().toString());
    }
}
