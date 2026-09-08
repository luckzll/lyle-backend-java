package com.example.aiapp.controller;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.security.EmailCodeService;
import com.example.aiapp.security.HumanVerificationService;
import com.example.aiapp.service.EmailIdentityService;
import com.example.aiapp.utils.CurrentUserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/email")
public class EmailAuthController {
    private final EmailCodeService codes;
    private final EmailIdentityService identities;
    private final AuthProperties properties;
    private final HumanVerificationService verification;
    public EmailAuthController(EmailCodeService codes, EmailIdentityService identities, AuthProperties properties, HumanVerificationService verification) {
        this.codes = codes; this.identities = identities; this.properties = properties;
        this.verification = verification;
    }
    public record EmailRequest(@NotBlank @Size(max=254) String email, @Size(max=2048) String captchaToken, @Size(max=256) String captchaRandstr) {}
    public record VerifyRequest(@NotBlank @Size(max=254) String email, @NotBlank @Size(min=6,max=6) String code) {}
    @GetMapping("/options")
    public Map<String, Object> options() { return Map.of("enabled", codes.enabled(), "captcha", verification.options()); }
    @PostMapping("/code")
    public Map<String, Object> code(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        EmailCodeService.normalize(body.email());
        verification.verify(body.captchaToken(), body.captchaRandstr(), request);
        codes.send(body.email(), "login");
        return Map.of("message", "验证码已发送，请检查收件箱或垃圾邮件。", "retryAfterSeconds", 60);
    }
    @PostMapping("/login")
    public AuthResponseVO login(@Valid @RequestBody VerifyRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = codes.verify(body.email(), body.code(), "login");
        var session = identities.login(email, request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(properties.refreshCookieName(), session.refreshToken())
                .httpOnly(true).secure(properties.secureCookie()).sameSite("Lax").path("/api/auth")
                .maxAge(Duration.ofDays(properties.refreshTtlDays())).build().toString());
        return session.response();
    }
    @GetMapping("/binding")
    public Map<String, String> binding() { return Map.of("email", identities.email(CurrentUserUtils.currentUserId())); }
    @PostMapping("/binding/code")
    public Map<String, Object> bindingCode(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        EmailCodeService.normalize(body.email());
        verification.verify(body.captchaToken(), body.captchaRandstr(), request);
        codes.send(body.email(), "bind:" + CurrentUserUtils.currentUserId());
        return Map.of("message", "验证码已发送。", "retryAfterSeconds", 60);
    }
    @PostMapping("/binding")
    public Map<String, String> bind(@Valid @RequestBody VerifyRequest body) {
        Long id = CurrentUserUtils.currentUserId();
        String email = codes.verify(body.email(), body.code(), "bind:" + id);
        identities.bind(id, email);
        return Map.of("email", email);
    }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> duplicate() {
        return ResponseEntity.status(409).body(Map.of("detail", "邮箱绑定冲突，请使用已绑定账号登录，或重新获取验证码。"));
    }
}
