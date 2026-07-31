package com.example.aiapp.security;

import com.example.aiapp.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    @Test
    void createsOpaqueRefreshTokenAndServerSideSession() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        AuthSessionService service = new AuthSessionService(jdbcTemplate, properties());

        AuthSessionService.SessionGrant grant = service.create(
                42L,
                3,
                "lyle",
                "USER",
                "ACTIVE",
                LocalDateTime.now(),
                "test-agent",
                "127.0.0.1"
        );

        assertThat(grant.refreshToken()).hasSizeGreaterThan(40);
        assertThat(grant.authVersion()).isEqualTo(3);
        verify(jdbcTemplate).update(
                contains("INSERT INTO auth_session"),
                any(Object[].class)
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO auth_refresh_token"),
                any(Object[].class)
        );
    }

    @Test
    void refreshReplayRevokesTheWholeSessionFamily() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcWithRefreshRow(LocalDateTime.now().minusMinutes(1), "ACTIVE", 3, 3);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        AuthSessionService service = new AuthSessionService(jdbcTemplate, properties());

        AuthSessionService.RotationResult result = service.rotate("replayed-token");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.reason()).isEqualTo("REFRESH_REPLAY");
        verify(jdbcTemplate).update(
                contains("UPDATE auth_session SET revoked_at"),
                any(Object[].class)
        );
        verify(jdbcTemplate).update(
                contains("UPDATE auth_refresh_token SET revoked_at"),
                any(Object[].class)
        );
    }

    @Test
    void refreshRotationClaimsOldTokenBeforeIssuingReplacement() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcWithRefreshRow(null, "ACTIVE", 3, 3);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        AuthSessionService service = new AuthSessionService(jdbcTemplate, properties());

        AuthSessionService.RotationResult result = service.rotate("valid-token");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.grant().refreshToken()).hasSizeGreaterThan(40);
        verify(jdbcTemplate).update(
                contains("used_at = ?, replaced_by_token_id = ?"),
                any(Object[].class)
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO auth_refresh_token"),
                any(Object[].class)
        );
    }

    @Test
    void lostRefreshRaceIsTreatedAsReplay() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcWithRefreshRow(null, "ACTIVE", 3, 3);
        when(jdbcTemplate.update(
                contains("used_at = ?, replaced_by_token_id = ?"),
                any(Object[].class)
        )).thenReturn(0);
        when(jdbcTemplate.update(
                contains("revoked_at"),
                any(Object[].class)
        )).thenReturn(1);
        AuthSessionService service = new AuthSessionService(jdbcTemplate, properties());

        AuthSessionService.RotationResult result = service.rotate("raced-token");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.reason()).isEqualTo("REFRESH_REPLAY");
    }

    @Test
    void disabledAccountCannotRefreshAndIsRevoked() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcWithRefreshRow(null, "DISABLED", 3, 3);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        AuthSessionService service = new AuthSessionService(jdbcTemplate, properties());

        AuthSessionService.RotationResult result = service.rotate("disabled-user-token");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.reason()).isEqualTo("INVALID_REFRESH_TOKEN");
        verify(jdbcTemplate, times(2)).update(
                contains("revoked_at"),
                any(Object[].class)
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JdbcTemplate jdbcWithRefreshRow(
            LocalDateTime usedAt,
            String status,
            int sessionAuthVersion,
            int userAuthVersion
    ) throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        LocalDateTime now = LocalDateTime.now();
        when(resultSet.getLong("token_id")).thenReturn(100L);
        when(resultSet.getLong("session_id")).thenReturn(200L);
        when(resultSet.getLong("user_id")).thenReturn(42L);
        when(resultSet.getInt("session_auth_version")).thenReturn(sessionAuthVersion);
        when(resultSet.getInt("user_auth_version")).thenReturn(userAuthVersion);
        when(resultSet.getTimestamp("token_expires_at")).thenReturn(Timestamp.valueOf(now.plusDays(1)));
        when(resultSet.getTimestamp("used_at")).thenReturn(timestamp(usedAt));
        when(resultSet.getTimestamp("token_revoked_at")).thenReturn(null);
        when(resultSet.getTimestamp("session_created_at")).thenReturn(Timestamp.valueOf(now.minusDays(1)));
        when(resultSet.getTimestamp("last_used_at")).thenReturn(Timestamp.valueOf(now.minusMinutes(5)));
        when(resultSet.getTimestamp("session_expires_at")).thenReturn(Timestamp.valueOf(now.plusDays(1)));
        when(resultSet.getTimestamp("session_revoked_at")).thenReturn(null);
        when(resultSet.getTimestamp("last_login_at")).thenReturn(Timestamp.valueOf(now.minusDays(1)));
        when(resultSet.getString("username")).thenReturn("lyle");
        when(resultSet.getString("role")).thenReturn("USER");
        when(resultSet.getString("status")).thenReturn(status);
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
        return jdbcTemplate;
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static AuthProperties properties() {
        return new AuthProperties(
                "test-secret-key-that-is-long-enough-for-hmac",
                "current-key",
                "",
                "",
                false,
                15,
                30,
                "lyle-ai",
                "lyle-web",
                "lyle_refresh",
                false,
                true,
                "http://127.0.0.1:4173"
        );
    }
}
