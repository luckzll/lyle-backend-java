package com.example.aiapp.controller;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.security.EmailCodeService;
import com.example.aiapp.security.HumanVerificationService;
import com.example.aiapp.service.LegacyAccountMigration;
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
@RequestMapping("/auth/migration")
public class LegacyMigrationController {
    private final LegacyAccountMigration migration;
    private final EmailCodeService codes;
    private final HumanVerificationService captcha;
    private final AuthProperties properties;
    public LegacyMigrationController(LegacyAccountMigration migration, EmailCodeService codes, HumanVerificationService captcha, AuthProperties properties) {
        this.migration=migration; this.codes=codes; this.captcha=captcha; this.properties=properties;
    }
    public record StartRequest(@NotBlank @Size(min=3,max=64) String username, @NotBlank @Size(max=128) String password,
            @Size(max=2048) String captchaToken, @Size(max=256) String captchaRandstr) {}
    @PostMapping("/start")
    public Map<String,Boolean> start(@Valid @RequestBody StartRequest body,
            @CookieValue(name=LegacyAccountMigration.COOKIE,required=false) String previous, HttpServletRequest request, HttpServletResponse response) {
        captcha.verify(body.captchaToken(),body.captchaRandstr(),request);
        String ticket = migration.begin(body.username(),body.password(),previous);
        cookie(response,ticket,600);
        return Map.of("requiresBinding",true);
    }
    @PostMapping("/cancel")
    public Map<String,Boolean> cancel(@CookieValue(name=LegacyAccountMigration.COOKIE,required=false) String ticket,HttpServletResponse response) {
        migration.cancel(ticket); cookie(response,"",0); return Map.of("cancelled",true);
    }
    @PostMapping("/email/code")
    public Map<String,Integer> code(@CookieValue(name=LegacyAccountMigration.COOKIE,required=false) String ticket,
            @Valid @RequestBody EmailAuthController.EmailRequest body,HttpServletRequest request) {
        migration.require(ticket,false); EmailCodeService.normalize(body.email());
        captcha.verify(body.captchaToken(),body.captchaRandstr(),request);
        codes.send(body.email(),"legacy:"+ticket); return Map.of("retryAfterSeconds",60);
    }
    @PostMapping("/email/login")
    public Object bind(@CookieValue(name=LegacyAccountMigration.COOKIE,required=false) String ticket,
            @Valid @RequestBody EmailAuthController.VerifyRequest body,HttpServletRequest request,HttpServletResponse response) {
        migration.require(ticket,false);
        String email = codes.verify(body.email(),body.code(),"legacy:"+ticket);
        Long userId = migration.require(ticket,true);
        var session = migration.bindEmail(userId,email,request.getHeader(HttpHeaders.USER_AGENT),request.getRemoteAddr());
        cookie(response,"",0);
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(properties.refreshCookieName(),session.refreshToken()).httpOnly(true)
                .secure(properties.secureCookie()).sameSite("Lax").path("/api/auth").maxAge(Duration.ofDays(properties.refreshTtlDays())).build().toString());
        return session.response();
    }
    private void cookie(HttpServletResponse response,String value,int age) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(LegacyAccountMigration.COOKIE,value).httpOnly(true)
                .secure(properties.secureCookie()).sameSite("Lax").path("/api/auth").maxAge(Duration.ofSeconds(age)).build().toString());
    }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String,String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("detail","该邮箱已属于其他账号，不能覆盖绑定。请重新验证旧账号并使用其他邮箱。"));
    }
}
