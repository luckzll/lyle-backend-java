package com.example.aiapp.controller;

import com.example.aiapp.config.*;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.domain.vo.PageVO;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.security.*;
import com.example.aiapp.service.AdminUserService;
import com.example.aiapp.service.impl.AuthServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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

@WebMvcTest(controllers = {AdminPasswordController.class, AdminUserController.class})
@ContextConfiguration(classes = {AdminPasswordController.class, AdminUserController.class, AuthServiceImpl.class,
        SecurityConfig.class, JwtService.class, JwtAuthenticationFilter.class, RateLimitFilter.class})
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class, RedisProperties.class})
@TestPropertySource(properties = {
        "app.auth.jwt-secret=synthetic-admin-login-test-key-at-least-thirty-two-bytes",
        "app.auth.jwt-key-id=test", "app.auth.jwt-ttl-minutes=15", "app.auth.refresh-ttl-days=30",
        "app.auth.issuer=lyle-ai", "app.auth.audience=lyle-web", "app.auth.refresh-cookie-name=lyle_refresh",
        "app.auth.secure-cookie=true", "app.auth.session-v64-enabled=true",
        "app.auth.allowed-origins=https://example.com", "app.rate-limit.auth-per-minute=3",
        "app.rate-limit.login-username-per-minute=2", "app.redis.enabled=false"
})
class AdminPasswordSecurityTest {
    private static final String PATH = "/auth/admin/password/login";
    private static final String CREDENTIAL_ERROR = "用户名或密码不正确，请重试。";
    private static final String CAPTCHA_ERROR = "安全验证未通过、已使用或已过期，请重新验证。";
    private static final String PROOF = "a".repeat(43);
    private static final AtomicInteger NEXT_IP = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwords;
    @MockBean UserMapper users;
    @MockBean AdminUserService adminUsers;
    @MockBean HumanVerificationService verification;
    @MockBean AuthSecurityEventService events;
    @MockBean AuthSessionService sessions;

    @Test void administratorLoginConsumesProofAndIssuesAnAdminTokenAndSecureRefreshCookie() throws Exception {
        var administrator = user("administrator-ok", "ADMIN", "ACTIVE", "synthetic-password");
        when(users.selectByUsernameForUpdate("administrator-ok")).thenReturn(administrator);
        var now = LocalDateTime.now();
        when(sessions.create(eq(42L), eq(3), eq("administrator-ok"), eq("ADMIN"), eq("ACTIVE"), any(), any(), any()))
                .thenReturn(new AuthSessionService.SessionGrant(22L, 3, 42L, "administrator-ok", "ADMIN", "ACTIVE",
                        now, now, now, now.plusDays(30), "synthetic-refresh"));

        var result = mvc.perform(jsonPost(PATH, body(" administrator-ok ", "synthetic-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(cookie().httpOnly("lyle_refresh", true)).andExpect(cookie().secure("lyle_refresh", true))
                .andExpect(cookie().path("lyle_refresh", "/api/auth")).andExpect(cookie().maxAge("lyle_refresh", 30 * 24 * 3600))
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax");
        String access = json.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
        assertThat(jwt.parse(access).get("role", String.class)).isEqualTo("ADMIN");
        assertThat(jwt.parse(access).get("sid", String.class)).isEqualTo("22");
        assertThat(jwt.parse(access).get("ver", Integer.class)).isEqualTo(3);
        var ordered = inOrder(verification, users, sessions);
        ordered.verify(verification).verify(eq(PROOF), isNull(), any());
        ordered.verify(users).selectByUsernameForUpdate("administrator-ok");
        ordered.verify(users).updateById(administrator);
        ordered.verify(sessions).create(eq(42L), eq(3), eq("administrator-ok"), eq("ADMIN"), eq("ACTIVE"), any(), any(), any());

        when(sessions.isAccessValid(22L, 42L, 3)).thenReturn(true);
        when(adminUsers.list(1, 20, "", "")).thenReturn(new PageVO<>(List.of(), 0, 1, 20));
        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        when(sessions.isAccessValid(22L, 42L, 3)).thenReturn(false);
        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
    }

    @Test void invalidOrMissingProofCannotCheckCredentialsOrSignASession() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, CAPTCHA_ERROR)).when(verification).verify(any(), any(), any());
        mvc.perform(jsonPost(PATH, body("invalid-proof-admin", "synthetic-password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(CAPTCHA_ERROR))
                .andExpect(cookie().doesNotExist("lyle_refresh"));
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, CAPTCHA_ERROR)).when(verification).verify(isNull(), any(), any());
        mvc.perform(jsonPost(PATH, Map.of("username", "missing-proof-admin", "password", "synthetic-password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(CAPTCHA_ERROR))
                .andExpect(cookie().doesNotExist("lyle_refresh"));
        verifyNoInteractions(users, sessions, events);
    }

    @Test void unavailableVerificationPreservesItsServiceFailureWithoutCheckingCredentials() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "安全验证服务暂不可用，请稍后重试。"))
                .when(verification).verify(any(), any(), any());
        mvc.perform(jsonPost(PATH, body("verification-unavailable", "synthetic-password")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.detail").value("安全验证服务暂不可用，请稍后重试。"))
                .andExpect(cookie().doesNotExist("lyle_refresh"));
        verifyNoInteractions(users, sessions, events);
    }

    @Test void accountRoleStatusAndPasswordFailuresHaveTheSamePublicMessage() throws Exception {
        when(users.selectByUsernameForUpdate("ordinary-login")).thenReturn(user("ordinary-login", "USER", "ACTIVE", "synthetic-password"));
        when(users.selectByUsernameForUpdate("disabled-login")).thenReturn(user("disabled-login", "ADMIN", "DISABLED", "synthetic-password"));
        when(users.selectByUsernameForUpdate("wrong-password-login")).thenReturn(user("wrong-password-login", "ADMIN", "ACTIVE", "different-password"));
        for (String username : List.of("missing-login", "ordinary-login", "disabled-login", "wrong-password-login")) {
            mvc.perform(jsonPost(PATH, body(username, "synthetic-password")))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(CREDENTIAL_ERROR))
                    .andExpect(cookie().doesNotExist("lyle_refresh"));
        }
        verify(verification, times(4)).verify(eq(PROOF), isNull(), any());
        verify(users, never()).updateById(any(UserEntity.class));
        verifyNoInteractions(sessions);
    }

    @Test void oversizedPasswordsConsumeProofButNeverReachAccountOrBcryptChecks() throws Exception {
        for (String password : new String[]{"a".repeat(73), "密".repeat(25)}) {
            mvc.perform(jsonPost(PATH, body("long-password-login", password)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(CREDENTIAL_ERROR))
                    .andExpect(cookie().doesNotExist("lyle_refresh"));
        }
        verify(verification, times(2)).verify(eq(PROOF), isNull(), any());
        verifyNoInteractions(users, sessions);
    }

    @Test void oversizedProofAndMalformedCredentialsAreRejectedWithoutAccountAccess() throws Exception {
        mvc.perform(jsonPost(PATH, Map.of("username", "bad-proof", "password", "synthetic-password", "captchaToken", "a".repeat(2049))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(CAPTCHA_ERROR));
        mvc.perform(jsonPost(PATH, Map.of("username", "", "password", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(CREDENTIAL_ERROR));
        verifyNoInteractions(verification, users, sessions);
    }

    @Test void usernameBudgetIsSharedAcrossIpCasingAndWhitespace() throws Exception {
        for (String username : List.of("limited-administrator", " Limited-Administrator ")) {
            mvc.perform(jsonPost(PATH, body(username, "synthetic-password"))).andExpect(status().isUnauthorized());
        }
        mvc.perform(jsonPost(PATH, body("LIMITED-ADMINISTRATOR", "synthetic-password"))).andExpect(status().isTooManyRequests());
        verify(verification, times(2)).verify(eq(PROOF), isNull(), any());
        verify(users, times(2)).selectByUsernameForUpdate(anyString());
    }

    @Test void ipBudgetCannotBeBypassedByChangingUsernameOrSpoofingForwarding() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(jsonPost(PATH, body("ip-limited-administrator-" + i, "synthetic-password"))
                            .header("X-Forwarded-For", "203.0.113." + (i + 1))
                            .with(request -> { request.setRemoteAddr("192.0.2.200"); return request; }))
                    .andExpect(status().is(i < 3 ? 401 : 429));
        }
        verify(verification, times(3)).verify(eq(PROOF), isNull(), any());
        verify(users, times(3)).selectByUsernameForUpdate(anyString());
    }

    @Test void authenticationBodyLimitRejectsOversizedRequestsBeforeVerification() throws Exception {
        mvc.perform(jsonPost(PATH, Map.of("username", "huge-request", "password", "synthetic-password", "padding", "x".repeat(16 * 1024))))
                .andExpect(status().isPayloadTooLarge()).andExpect(cookie().doesNotExist("lyle_refresh"));
        verifyNoInteractions(verification, users, sessions);
    }

    @Test void onlyTheExactPostLoginRouteIsPublicAndLegacyRoutesStayClosed() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String path : List.of("/auth/login", "/auth/register", "/auth/change-password", "/auth/admin/password/reset", PATH + "-extra")) {
            mvc.perform(jsonPost(path, body("closed-route", "synthetic-password"))).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(verification, users, sessions);
    }

    private UserEntity user(String username, String role, String status, String password) {
        var user = new UserEntity();
        user.setId(42L); user.setUsername(username); user.setRole(role); user.setStatus(status);
        user.setAuthVersion(3); user.setPasswordHash(passwords.encode(password));
        return user;
    }

    private Map<String, String> body(String username, String password) {
        return Map.of("username", username, "password", password, "captchaToken", PROOF);
    }

    private MockHttpServletRequestBuilder jsonPost(String path, Map<String, String> body) throws Exception {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                .with(request -> { request.setRemoteAddr("198.51.100." + NEXT_IP.incrementAndGet()); return request; });
    }
}
