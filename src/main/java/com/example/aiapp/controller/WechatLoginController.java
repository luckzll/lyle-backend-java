package com.example.aiapp.controller;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.config.WechatLoginProperties;
import com.example.aiapp.security.EmailCodeService;
import com.example.aiapp.security.HumanVerificationService;
import com.example.aiapp.security.WechatLoginFlow;
import com.example.aiapp.service.AuthService;
import com.example.aiapp.service.WechatIdentityService;
import com.example.aiapp.service.LegacyAccountMigration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/wechat")
public class WechatLoginController {
    static final String COOKIE = "lyle_wechat_flow";
    private final WechatLoginProperties properties;
    private final AuthProperties authProperties;
    private final WechatLoginFlow flows;
    private final WechatIdentityService identities;
    private final EmailCodeService codes;
    private final HumanVerificationService captcha;
    private final LegacyAccountMigration migration;
    public WechatLoginController(WechatLoginProperties properties, AuthProperties authProperties, WechatLoginFlow flows,
            WechatIdentityService identities, EmailCodeService codes, HumanVerificationService captcha, LegacyAccountMigration migration) {
        this.properties = properties; this.authProperties = authProperties; this.flows = flows;
        this.identities = identities; this.codes = codes; this.captcha = captcha;
        this.migration = migration;
    }
    @GetMapping("/options")
    public Map<String, Object> options() { return Map.of("enabled", properties.configured()); }

    @PostMapping("/start")
    public Map<String, Object> start(@CookieValue(name=COOKIE, required=false) String previous,
            @CookieValue(name=LegacyAccountMigration.COOKIE, required=false) String migrationTicket,
            @RequestBody(required=false) StartRequest body, HttpServletResponse response) {
        noCache(response);
        var flow = body != null && body.legacy() ? flows.start(previous, migration.require(migrationTicket,false)) : flows.start(previous);
        flowCookie(response, flow.browserToken(), flow.expiresInSeconds());
        return Map.of("qrUrl", flow.qrUrl(), "expiresInSeconds", flow.expiresInSeconds());
    }
    public record StartRequest(boolean legacy) {}
    @PostMapping("/cancel")
    public Map<String, Boolean> cancel(@CookieValue(name=COOKIE, required=false) String browser, HttpServletResponse response) {
        flows.cancel(browser); flowCookie(response, "", 0); noCache(response);
        return Map.of("cancelled", true);
    }
    @GetMapping("/callback")
    public void callback(@CookieValue(name=COOKIE, required=false) String browser,
            @RequestParam(required=false) String state, @RequestParam(required=false) String code,
            HttpServletResponse response) throws IOException {
        noCache(response);
        String result = "complete";
        try {
            flows.callback(browser, state, code);
            flowCookie(response, browser, WechatLoginFlow.TTL_SECONDS);
        } catch (RuntimeException e) {
            // Use only a fixed error flag; never echo provider inputs/errors/tokens into HTML/URLs/logs.
            result = "failed";
        }
        response.sendRedirect("/login?wechat=" + result);
    }
    @PostMapping("/complete")
    public Object complete(@CookieValue(name=COOKIE, required=false) String browser,
            @CookieValue(name=LegacyAccountMigration.COOKIE, required=false) String migrationTicket,
            HttpServletRequest request, HttpServletResponse response) {
        noCache(response);
        String openId = flows.identity(browser, false);
        Long legacyUser = flows.legacyUser(browser);
        if (legacyUser != null) {
            if (!legacyUser.equals(migration.require(migrationTicket,false)))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"旧账号验证不匹配，请重新验证。");
            openId = flows.identity(browser,true);
            Long id = migration.require(migrationTicket,true);
            return finish(migration.bindWechat(id,openId,request.getHeader(HttpHeaders.USER_AGENT),request.getRemoteAddr()),response);
        }
        if (identities.user(openId).isEmpty()) return Map.of("requiresEmail", true);
        openId = flows.identity(browser, true);
        var session = identities.login(openId, request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
        return finish(session, response);
    }
    @PostMapping("/email/code")
    public Map<String, Object> code(@CookieValue(name=COOKIE, required=false) String browser,
            @Valid @RequestBody EmailAuthController.EmailRequest body, HttpServletRequest request, HttpServletResponse response) {
        noCache(response); flows.identity(browser, false); requireNormalFlow(browser);
        EmailCodeService.normalize(body.email());
        captcha.verify(body.captchaToken(), body.captchaRandstr(), request);
        codes.send(body.email(), "wechat-link:" + browser);
        return Map.of("retryAfterSeconds", 60);
    }
    @PostMapping("/email/login")
    public Object link(@CookieValue(name=COOKIE, required=false) String browser,
            @Valid @RequestBody EmailAuthController.VerifyRequest body, HttpServletRequest request, HttpServletResponse response) {
        noCache(response); flows.identity(browser, false); requireNormalFlow(browser);
        String email = codes.verify(body.email(), body.code(), "wechat-link:" + browser);
        String openId = flows.identity(browser, true);
        var session = identities.link(openId, email, request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
        return finish(session, response);
    }
    private Object finish(AuthService.AuthenticatedSession session, HttpServletResponse response) {
        flowCookie(response, "", 0);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(LegacyAccountMigration.COOKIE, "")
                .httpOnly(true).secure(authProperties.secureCookie()).sameSite("Lax").path("/api/auth")
                .maxAge(Duration.ZERO).build().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(authProperties.refreshCookieName(), session.refreshToken())
                .httpOnly(true).secure(authProperties.secureCookie()).sameSite("Lax").path("/api/auth")
                .maxAge(Duration.ofDays(authProperties.refreshTtlDays())).build().toString());
        return session.response();
    }
    private void requireNormalFlow(String browser) {
        if (flows.legacyUser(browser) != null)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "请完成原账号绑定，不可切换为新账号注册。");
    }
    private void flowCookie(HttpServletResponse response, String value, int seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, value).httpOnly(true)
                .secure(authProperties.secureCookie()).sameSite("Lax").path("/api/auth/wechat")
                .maxAge(Duration.ofSeconds(seconds)).build().toString());
    }
    private void noCache(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("detail", "账号已有微信绑定或正在其他页面关联，请重新扫码或使用邮箱登录。"));
    }
}
