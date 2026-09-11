package com.example.aiapp.controller;

import com.example.aiapp.config.AuthProperties;
import com.example.aiapp.config.RateLimitProperties;
import com.example.aiapp.config.RedisProperties;
import com.example.aiapp.config.SecurityConfig;
import com.example.aiapp.domain.vo.AuthResponseVO;
import com.example.aiapp.domain.vo.UserVO;
import com.example.aiapp.security.AuthSecurityEventService;
import com.example.aiapp.security.AuthSessionService;
import com.example.aiapp.security.EmailCodeService;
import com.example.aiapp.security.HumanVerificationService;
import com.example.aiapp.security.JwtAuthenticationFilter;
import com.example.aiapp.security.JwtService;
import com.example.aiapp.security.RateLimitFilter;
import com.example.aiapp.service.AuthService;
import com.example.aiapp.service.EmailIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = EmailPasswordController.class)
@ContextConfiguration(classes = {EmailPasswordController.class, SecurityConfig.class,
        JwtService.class, JwtAuthenticationFilter.class, RateLimitFilter.class})
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class, RedisProperties.class})
@TestPropertySource(properties = {
        "app.auth.jwt-secret=test-secret-key-that-is-long-enough-for-hmac",
        "app.auth.jwt-key-id=test-current",
        "app.auth.jwt-ttl-minutes=15",
        "app.auth.refresh-ttl-days=30",
        "app.auth.issuer=lyle-ai",
        "app.auth.audience=lyle-web",
        "app.auth.refresh-cookie-name=lyle_refresh",
        "app.auth.secure-cookie=true",
        "app.auth.session-v64-enabled=true",
        "app.auth.allowed-origins=https://example.com",
        "app.rate-limit.login-username-per-minute=2",
        "app.redis.enabled=false"
})
class EmailPasswordSecurityTest {
    private static final AtomicInteger NEXT_IP = new AtomicInteger();
    private static final String CREDENTIAL_ERROR = "邮箱或密码不正确，请重试。";
    private static final String CAPTCHA_ERROR = "安全验证未通过、已使用或已过期，请重新验证。";
    private static final String PROOF = "a".repeat(43);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean EmailCodeService codes;
    @MockBean EmailIdentityService identities;
    @MockBean HumanVerificationService verification;
    @MockBean AuthSecurityEventService events;
    @MockBean AuthSessionService sessions;

    @Test
    void passwordLoginVerifiesProofBeforeCredentialsAndDoesNotRequireMail() throws Exception {
        when(identities.loginWithPassword(eq("login@example.com"), eq("password-123"), any(), any()))
                .thenReturn(session());

        mvc.perform(jsonPost("/auth/email/password/login", passwordLoginBody(" Login@Example.COM ", "password-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(42))
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(cookie().httpOnly("lyle_refresh", true))
                .andExpect(cookie().secure("lyle_refresh", true))
                .andExpect(cookie().path("lyle_refresh", "/api/auth"))
                .andExpect(cookie().maxAge("lyle_refresh", 30 * 24 * 3600));
        var ordered = inOrder(verification, identities);
        ordered.verify(verification).verify(eq(PROOF), eq("legacy-compatible"), any());
        ordered.verify(identities).loginWithPassword(eq("login@example.com"), eq("password-123"), any(), any());
        verifyNoInteractions(codes);
    }

    @Test
    void missingPasswordLoginProofCannotCheckCredentialsOrIssueSession() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, CAPTCHA_ERROR))
                .when(verification).verify(isNull(), any(), any());

        mvc.perform(jsonPost("/auth/email/password/login", Map.of(
                        "email", "missing-proof@example.com", "password", "password-123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(CAPTCHA_ERROR))
                .andExpect(cookie().doesNotExist("lyle_refresh"));

        verify(verification).verify(isNull(), isNull(), any());
        verifyNoInteractions(identities, codes, events);
    }

    @Test
    void rejectedPasswordLoginProofCannotCheckCredentialsOrIssueSession() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, CAPTCHA_ERROR))
                .when(verification).verify(eq("invalid-proof"), any(), any());

        mvc.perform(jsonPost("/auth/email/password/login", Map.of(
                        "email", "invalid-proof@example.com", "password", "password-123", "captchaToken", "invalid-proof")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(CAPTCHA_ERROR))
                .andExpect(cookie().doesNotExist("lyle_refresh"));

        verify(verification).verify(eq("invalid-proof"), isNull(), any());
        verifyNoInteractions(identities, codes, events);
    }

    @Test
    void unavailableVerificationCannotCheckCredentialsAndKeepsServiceError() throws Exception {
        String detail = "安全验证服务暂不可用，请稍后重试。";
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail))
                .when(verification).verify(any(), any(), any());

        mvc.perform(jsonPost("/auth/email/password/login", passwordLoginBody("unavailable-proof@example.com", "password-123")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(cookie().doesNotExist("lyle_refresh"));

        verifyNoInteractions(identities, codes, events);
    }

    @Test
    void oversizedPasswordLoginProofIsReportedAsVerificationError() throws Exception {
        mvc.perform(jsonPost("/auth/email/password/login", Map.of(
                        "email", "oversized-proof@example.com", "password", "password-123", "captchaToken", "a".repeat(2049))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(CAPTCHA_ERROR))
                .andExpect(cookie().doesNotExist("lyle_refresh"));

        verifyNoInteractions(verification, identities, codes, events);
    }

    @Test
    void credentialFailuresDoNotRevealInternalAccountDetails() throws Exception {
        when(identities.loginWithPassword(anyString(), anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用，请联系管理员。"));
        mvc.perform(jsonPost("/auth/email/password/login", passwordLoginBody("denied@example.com", "incorrect-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(CREDENTIAL_ERROR))
                .andExpect(cookie().doesNotExist("lyle_refresh"));
        var ordered = inOrder(verification, identities);
        ordered.verify(verification).verify(eq(PROOF), eq("legacy-compatible"), any());
        ordered.verify(identities).loginWithPassword(eq("denied@example.com"), eq("incorrect-password"), any(), any());
        verifyNoInteractions(codes);
    }

    @Test
    void invalidLoginFieldsUseTheSameCredentialMessage() throws Exception {
        mvc.perform(jsonPost("/auth/email/password/login", Map.of("email", "", "password", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(CREDENTIAL_ERROR));
        verifyNoInteractions(identities, codes);
    }

    @Test
    void oversizedLoginPasswordsCannotUseBcryptPrefixTruncation() throws Exception {
        for (String password : new String[]{"a".repeat(73), "密".repeat(25)}) {
            mvc.perform(jsonPost("/auth/email/password/login", passwordLoginBody("long-login@example.com", password)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value(CREDENTIAL_ERROR))
                    .andExpect(cookie().doesNotExist("lyle_refresh"));
        }
        verifyNoInteractions(identities, codes);
    }

    @Test
    void passwordCodeIsPublicAndRequiresCaptchaBeforeSendingWithDistinctPurpose() throws Exception {
        mvc.perform(jsonPost("/auth/email/password/code", Map.of(
                        "email", "reset-code@example.com", "captchaToken", "proof")))
                .andExpect(status().isOk());
        var ordered = inOrder(verification, codes);
        ordered.verify(verification).verify(eq("proof"), isNull(), any());
        ordered.verify(codes).send("reset-code@example.com", "password");
        verifyNoInteractions(identities);
    }

    @Test
    void rejectedCaptchaNeverSendsPasswordCode() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证失败"))
                .when(verification).verify(any(), any(), any());
        mvc.perform(jsonPost("/auth/email/password/code", Map.of(
                        "email", "captcha-failed@example.com", "captchaToken", "invalid")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(codes, identities);
    }

    @Test
    void resetIsPublicAndUsesOnlyVerifiedPasswordPurposeIdentity() throws Exception {
        when(codes.verify(" Reset@Example.com ", "123456", "password")).thenReturn("reset@example.com");
        when(identities.resetPassword(eq("reset@example.com"), eq("new-password"), any(), any())).thenReturn(session());
        var result = mvc.perform(jsonPost("/auth/email/password/reset", Map.of(
                        "email", " Reset@Example.com ", "code", "123456", "password", "new-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(42))
                .andExpect(cookie().httpOnly("lyle_refresh", true))
                .andExpect(cookie().secure("lyle_refresh", true))
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax");
        var ordered = inOrder(codes, identities);
        ordered.verify(codes).verify(" Reset@Example.com ", "123456", "password");
        ordered.verify(identities).resetPassword(eq("reset@example.com"), eq("new-password"), any(), any());
        verifyNoInteractions(verification);
    }

    @Test
    void invalidOrWrongPurposeCodeCannotResetPassword() throws Exception {
        when(codes.verify("invalid-code@example.com", "123456", "password"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码错误、过期或尝试次数过多，请重新获取。"));
        mvc.perform(jsonPost("/auth/email/password/reset", Map.of(
                        "email", "invalid-code@example.com", "code", "123456", "password", "new-password")))
                .andExpect(status().isBadRequest())
                .andExpect(cookie().doesNotExist("lyle_refresh"));
        verifyNoInteractions(identities);
    }

    @Test
    void oversizedUtf8PasswordIsRejectedBeforeConsumingCode() throws Exception {
        mvc.perform(jsonPost("/auth/email/password/reset", Map.of(
                        "email", "long-password@example.com", "code", "123456", "password", "密".repeat(25))))
                .andExpect(status().isBadRequest())
                .andExpect(cookie().doesNotExist("lyle_refresh"));
        verifyNoInteractions(codes, identities);
    }

    @Test
    void passwordLoginLimitsSameEmailAcrossDifferentIpsAndCasing() throws Exception {
        when(identities.loginWithPassword(anyString(), anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, CREDENTIAL_ERROR));
        for (String email : new String[]{"rate-email@example.com", " Rate-Email@Example.COM "}) {
            mvc.perform(jsonPost("/auth/email/password/login", passwordLoginBody(email, "wrong")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(jsonPost("/auth/email/password/login", passwordLoginBody("RATE-EMAIL@example.com", "wrong")))
                .andExpect(status().isTooManyRequests());
        verify(identities, times(2)).loginWithPassword(anyString(), anyString(), any(), any());
        verify(verification, times(2)).verify(eq(PROOF), eq("legacy-compatible"), any());
    }

    @Test
    void passwordLoginLimitsIpAcrossDifferentEmailsAndIgnoresUntrustedForwarding() throws Exception {
        when(identities.loginWithPassword(anyString(), anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, CREDENTIAL_ERROR));
        for (int i = 0; i < 11; i++) {
            mvc.perform(jsonPost("/auth/email/password/login", passwordLoginBody("rate-ip-" + i + "@example.com", "wrong"))
                            .header("X-Forwarded-For", "203.0.113." + (i + 1))
                            .with(request -> { request.setRemoteAddr("192.0.2.100"); return request; }))
                    .andExpect(status().is(i < 10 ? 401 : 429));
        }
        verify(identities, times(10)).loginWithPassword(anyString(), anyString(), any(), any());
        verify(verification, times(10)).verify(eq(PROOF), eq("legacy-compatible"), any());
    }

    @Test
    void newPublicRoutesDoNotExposeEmailBindingOrLogout() throws Exception {
        for (String path : new String[]{"/auth/email/binding", "/auth/email/binding/code", "/auth/logout-all"}) {
            mvc.perform(jsonPost(path, Map.of("email", "binding@example.com", "code", "123456")))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(codes, identities);
    }

    private MockHttpServletRequestBuilder jsonPost(String path, Map<String, String> body) throws Exception {
        String address = "198.51.100." + NEXT_IP.incrementAndGet();
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                .with(request -> { request.setRemoteAddr(address); return request; });
    }

    private Map<String, String> passwordLoginBody(String email, String password) {
        return Map.of("email", email, "password", password, "captchaToken", PROOF, "captchaRandstr", "legacy-compatible");
    }

    private AuthService.AuthenticatedSession session() {
        return new AuthService.AuthenticatedSession(
                new AuthResponseVO(new UserVO(42L, "existing-user", "USER"), "access"), "refresh-test");
    }
}
