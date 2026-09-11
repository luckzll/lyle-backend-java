package com.example.aiapp.service;

import com.example.aiapp.domain.dto.AuthRequestDTO;
import com.example.aiapp.domain.entity.UserEntity;
import com.example.aiapp.mapper.UserMapper;
import com.example.aiapp.security.AuthSessionService;
import com.example.aiapp.security.JwtService;
import com.example.aiapp.service.impl.AuthServiceImpl;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServicePasswordTest {
    private final UserMapper users = mock(UserMapper.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final JwtService jwt = mock(JwtService.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AuthServiceImpl auth = new AuthServiceImpl(users, passwords, jwt, sessions);

    @Test void verifiedPasswordResetReplacesHashRevokesOldSessionsAndIssuesNewVersion() {
        UserEntity user = user();
        String originalHash = user.getPasswordHash();
        when(users.selectAccountById(7L)).thenReturn(user);
        grantSession(2);

        var result = auth.resetVerifiedPassword(7L, "new-password123", "browser", "ip");

        assertThat(user.getPasswordHash()).isNotEqualTo(originalHash).startsWith("$2a$");
        assertThat(passwords.matches("new-password123", user.getPasswordHash())).isTrue();
        assertThat(passwords.matches("old-password", user.getPasswordHash())).isFalse();
        assertThat(user.getAuthVersion()).isEqualTo(2);
        assertThat(user.getPasswordChangedAt()).isNotNull();
        var order = inOrder(users, sessions);
        order.verify(users).updateById(user);
        order.verify(sessions).revokeAll(7L, "EMAIL_PASSWORD_RESET");
        order.verify(sessions).create(eq(7L), eq(2), eq("original-user"), eq("USER"), eq("ACTIVE"), any(), eq("browser"), eq("ip"));
        verify(jwt).generateToken(7L, "original-user", "USER", 22L, 2);
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
    }

    @Test void shortOrOversizedMultibytePasswordsCannotChangeCredentials() {
        for (String password : new String[] { "short", "a".repeat(65), "中".repeat(25), " ".repeat(8) }) {
            assertThatThrownBy(() -> auth.resetVerifiedPassword(7L, password, "browser", "ip"))
                    .isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(users, sessions);
    }

    @Test void disabledAccountCannotSetPassword() {
        UserEntity user = user();
        user.setStatus("DISABLED");
        when(users.selectAccountById(7L)).thenReturn(user);
        assertThatThrownBy(() -> auth.resetVerifiedPassword(7L, "new-password123", "browser", "ip"))
                .isInstanceOf(ResponseStatusException.class);
        verify(users, never()).updateById(any(UserEntity.class));
        verifyNoInteractions(sessions);
    }

    @Test void correctPasswordUsesExistingAccountAndWrongPasswordCreatesNoSession() {
        UserEntity user = user();
        when(users.selectByUsername("original-user")).thenReturn(user);
        assertThatThrownBy(() -> auth.login(new AuthRequestDTO("original-user", "wrong-password"), "browser", "ip"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(sessions);
        grantSession(1);
        auth.login(new AuthRequestDTO("original-user", "old-password"), "browser", "ip");
        verify(sessions).create(eq(7L), eq(1), eq("original-user"), eq("USER"), eq("ACTIVE"), any(), eq("browser"), eq("ip"));
        verify(users, never()).insert(any(UserEntity.class));
    }

    private UserEntity user() {
        UserEntity user = new UserEntity();
        user.setId(7L); user.setUsername("original-user"); user.setRole("USER");
        user.setStatus("ACTIVE"); user.setAuthVersion(1);
        user.setPasswordHash(passwords.encode("old-password"));
        return user;
    }

    private void grantSession(int version) {
        LocalDateTime now = LocalDateTime.now();
        when(sessions.create(eq(7L), eq(version), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new AuthSessionService.SessionGrant(22L, version, 7L, "original-user", "USER", "ACTIVE",
                        now, now, now, now.plusDays(30), "new-refresh"));
        when(jwt.generateToken(7L, "original-user", "USER", 22L, version)).thenReturn("new-access");
    }
}
