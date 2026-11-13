package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.security.AuthSessionService;
import com.example.aiapp.security.JwtService;
import com.example.aiapp.service.impl.AuthServiceImpl;
import com.example.aiapp.utils.PasswordUtils;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceAdministratorTest {
    private final UserMapper users = mock(UserMapper.class);
    private final BCryptPasswordEncoder passwords = spy(new BCryptPasswordEncoder(4));
    private final JwtService jwt = mock(JwtService.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AuthServiceImpl auth = new AuthServiceImpl(users, passwords, jwt, sessions);

    @Test void activeAdministratorUsesExistingAccountAndSignsAdministratorSession() {
        var administrator = user("ADMIN", "ACTIVE", "test-admin-password");
        when(users.selectByUsernameForUpdate("test-administrator")).thenReturn(administrator);
        grantSession();

        var result = auth.loginAdministrator(new AuthRequestDTO("test-administrator", "test-admin-password"), "browser", "ip");

        assertThat(result.refreshToken()).isEqualTo("synthetic-refresh");
        assertThat(result.response().user().role()).isEqualTo("ADMIN");
        assertThat(administrator.getLastLoginAt()).isNotNull();
        var ordered = inOrder(users, sessions);
        ordered.verify(users).selectByUsernameForUpdate("test-administrator");
        ordered.verify(users).updateById(administrator);
        ordered.verify(sessions).create(eq(7L), eq(3), eq("test-administrator"), eq("ADMIN"), eq("ACTIVE"), any(), eq("browser"), eq("ip"));
        verify(jwt).generateToken(7L, "test-administrator", "ADMIN", 22L, 3);
        verify(users, never()).insert(any(UserEntity.class));
        verify(users, never()).selectByUsername(anyString());
    }

    @Test void missingOrdinaryDisabledAndWrongPasswordAccountsHaveIdenticalFailures() {
        var ordinary = user("USER", "ACTIVE", "test-admin-password");
        var disabled = user("ADMIN", "DISABLED", "test-admin-password");
        var wrongPassword = user("ADMIN", "ACTIVE", "a-different-password");
        when(users.selectByUsernameForUpdate("ordinary")).thenReturn(ordinary);
        when(users.selectByUsernameForUpdate("disabled")).thenReturn(disabled);
        when(users.selectByUsernameForUpdate("wrong-password")).thenReturn(wrongPassword);
        for (String username : new String[]{"missing", "ordinary", "disabled", "wrong-password"}) {
            assertThatThrownBy(() -> auth.loginAdministrator(new AuthRequestDTO(username, "test-admin-password"), "browser", "ip"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                        assertThat(error.getStatusCode().value()).isEqualTo(401);
                        assertThat(error.getReason()).isEqualTo("用户名或密码不正确，请重试。");
                    });
        }
        verify(users, never()).updateById(any(UserEntity.class));
        verify(users, never()).insert(any(UserEntity.class));
        verifyNoInteractions(sessions, jwt);
    }

    @Test void successfulLegacyAdministratorLoginUpgradesOnlyThePasswordHash() {
        var administrator = user("ADMIN", "ACTIVE", "test-legacy-password");
        administrator.setPasswordHash(PasswordUtils.hash("test-legacy-password"));
        when(users.selectByUsernameForUpdate("test-administrator")).thenReturn(administrator);
        grantSession();

        auth.loginAdministrator(new AuthRequestDTO("test-administrator", "test-legacy-password"), "browser", "ip");

        assertThat(administrator.getPasswordHash()).startsWith("$2a$");
        assertThat(passwords.matches("test-legacy-password", administrator.getPasswordHash())).isTrue();
        assertThat(administrator.getRole()).isEqualTo("ADMIN");
        assertThat(administrator.getAuthVersion()).isEqualTo(3);
        verify(users).updateById(administrator);
        verify(sessions, never()).revokeAll(anyLong(), anyString());
    }

    @Test void oversizedUtf8PasswordsAreRejectedBeforeAnyAccountOrPasswordAccess() {
        for (String password : new String[]{"a".repeat(73), "密".repeat(25)}) {
            assertThatThrownBy(() -> auth.loginAdministrator(new AuthRequestDTO("test-administrator", password), "browser", "ip"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        }
        verifyNoInteractions(users, passwords, sessions, jwt);
    }

    @Test void exactSeventyTwoBytePasswordRemainsUsable() {
        String password = "密".repeat(24);
        var administrator = user("ADMIN", "ACTIVE", password);
        when(users.selectByUsernameForUpdate("test-administrator")).thenReturn(administrator);
        grantSession();
        assertThat(auth.loginAdministrator(new AuthRequestDTO("test-administrator", password), "browser", "ip").response().user().role())
                .isEqualTo("ADMIN");
    }

    private UserEntity user(String role, String status, String password) {
        var user = new UserEntity();
        user.setId(7L); user.setUsername("test-administrator"); user.setRole(role);
        user.setStatus(status); user.setAuthVersion(3); user.setPasswordHash(passwords.encode(password));
        return user;
    }

    private void grantSession() {
        var now = LocalDateTime.now();
        when(sessions.create(eq(7L), eq(3), eq("test-administrator"), eq("ADMIN"), eq("ACTIVE"), any(), anyString(), anyString()))
                .thenReturn(new AuthSessionService.SessionGrant(22L, 3, 7L, "test-administrator", "ADMIN", "ACTIVE",
                        now, now, now, now.plusDays(30), "synthetic-refresh"));
        when(jwt.generateToken(7L, "test-administrator", "ADMIN", 22L, 3)).thenReturn("synthetic-access");
    }
}
