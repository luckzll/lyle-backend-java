package com.example.aiapp.security;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.aiapp.config.AuthProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class AuthSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final AuthProperties properties;

    public AuthSessionService(JdbcTemplate jdbcTemplate, AuthProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Transactional
    public SessionGrant create(
            Long userId,
            int authVersion,
            String username,
            String role,
            String status,
            LocalDateTime lastLoginAt,
            String userAgent,
            String remoteAddress
    ) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusDays(properties.refreshTtlDays());
        long sessionId = IdWorker.getId();
        long tokenId = IdWorker.getId();
        String refreshToken = randomToken();
        jdbcTemplate.update(
                """
                INSERT INTO auth_session (
                    id, user_id, token_family_id, auth_version, created_at, last_used_at, expires_at,
                    user_agent_hash, ip_prefix_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId,
                userId,
                UUID.randomUUID().toString(),
                authVersion,
                now,
                now,
                expiresAt,
                digest(nullToEmpty(userAgent)),
                digest(nullToEmpty(remoteAddress))
        );
        jdbcTemplate.update(
                """
                INSERT INTO auth_refresh_token (
                    id, session_id, refresh_token_hash, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                tokenId,
                sessionId,
                digest(refreshToken),
                now,
                expiresAt
        );
        return new SessionGrant(
                sessionId,
                authVersion,
                userId,
                username,
                role,
                status,
                lastLoginAt,
                now,
                now,
                expiresAt,
                refreshToken
        );
    }

    @Transactional
    public RotationResult rotate(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return RotationResult.failure("MISSING_REFRESH_TOKEN");
        }
        List<RefreshRow> rows = jdbcTemplate.query(
                """
                SELECT
                    t.id AS token_id, t.session_id, t.expires_at AS token_expires_at,
                    t.used_at, t.revoked_at AS token_revoked_at,
                    s.user_id, s.auth_version AS session_auth_version, s.created_at AS session_created_at,
                    s.last_used_at, s.expires_at AS session_expires_at, s.revoked_at AS session_revoked_at,
                    u.username, u.role, u.status, u.auth_version AS user_auth_version, u.last_login_at
                FROM auth_refresh_token t
                JOIN auth_session s ON s.id = t.session_id
                JOIN app_user u ON u.id = s.user_id
                WHERE t.refresh_token_hash = ?
                FOR UPDATE
                """,
                (resultSet, rowNum) -> mapRefreshRow(resultSet),
                digest(rawRefreshToken)
        );
        if (rows.isEmpty()) {
            return RotationResult.failure("INVALID_REFRESH_TOKEN");
        }

        RefreshRow row = rows.get(0);
        LocalDateTime now = LocalDateTime.now();
        if (row.usedAt() != null) {
            revokeSession(row.sessionId(), "REFRESH_REPLAY", now);
            return RotationResult.failure("REFRESH_REPLAY");
        }
        if (row.tokenRevokedAt() != null
                || row.sessionRevokedAt() != null
                || !row.tokenExpiresAt().isAfter(now)
                || !row.sessionExpiresAt().isAfter(now)
                || !"ACTIVE".equalsIgnoreCase(row.status())
                || row.sessionAuthVersion() != row.userAuthVersion()) {
            revokeSession(row.sessionId(), "REFRESH_INVALID", now);
            return RotationResult.failure("INVALID_REFRESH_TOKEN");
        }

        String replacement = randomToken();
        long replacementId = IdWorker.getId();
        int claimed = jdbcTemplate.update(
                "UPDATE auth_refresh_token SET used_at = ?, replaced_by_token_id = ? WHERE id = ? AND used_at IS NULL",
                now,
                replacementId,
                row.tokenId()
        );
        if (claimed != 1) {
            revokeSession(row.sessionId(), "REFRESH_REPLAY", now);
            return RotationResult.failure("REFRESH_REPLAY");
        }
        jdbcTemplate.update(
                """
                INSERT INTO auth_refresh_token (
                    id, session_id, refresh_token_hash, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                replacementId,
                row.sessionId(),
                digest(replacement),
                now,
                row.sessionExpiresAt()
        );
        jdbcTemplate.update("UPDATE auth_session SET last_used_at = ? WHERE id = ?", now, row.sessionId());

        return RotationResult.success(new SessionGrant(
                row.sessionId(),
                row.userAuthVersion(),
                row.userId(),
                row.username(),
                role(row.role()),
                row.status(),
                row.lastLoginAt(),
                row.sessionCreatedAt(),
                now,
                row.sessionExpiresAt(),
                replacement
        ));
    }

    public boolean isAccessValid(Long sessionId, Long userId, int authVersion) {
        if (!properties.sessionV64Enabled()) {
            return true;
        }
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_session s
                JOIN app_user u ON u.id = s.user_id
                WHERE s.id = ?
                  AND s.user_id = ?
                  AND s.auth_version = ?
                  AND u.auth_version = ?
                  AND u.status = 'ACTIVE'
                  AND s.revoked_at IS NULL
                  AND s.expires_at > CURRENT_TIMESTAMP(6)
                """,
                Integer.class,
                sessionId,
                userId,
                authVersion,
                authVersion
        );
        return count != null && count == 1;
    }

    @Transactional
    public void revokeCurrent(Long sessionId, Long userId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                UPDATE auth_session
                SET revoked_at = COALESCE(revoked_at, ?), revoke_reason = COALESCE(revoke_reason, ?)
                WHERE id = ? AND user_id = ?
                """,
                now,
                reason,
                sessionId,
                userId
        );
        jdbcTemplate.update(
                """
                UPDATE auth_refresh_token t
                JOIN auth_session s ON s.id = t.session_id
                SET t.revoked_at = COALESCE(t.revoked_at, ?)
                WHERE s.id = ? AND s.user_id = ?
                """,
                now,
                sessionId,
                userId
        );
    }

    @Transactional
    public void revokeAll(Long userId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                UPDATE auth_session
                SET revoked_at = COALESCE(revoked_at, ?), revoke_reason = COALESCE(revoke_reason, ?)
                WHERE user_id = ?
                """,
                now,
                reason,
                userId
        );
        jdbcTemplate.update(
                """
                UPDATE auth_refresh_token t
                JOIN auth_session s ON s.id = t.session_id
                SET t.revoked_at = COALESCE(t.revoked_at, ?)
                WHERE s.user_id = ?
                """,
                now,
                userId
        );
    }

    @Transactional
    public void keepCurrentAfterCredentialChange(Long userId, Long currentSessionId, int authVersion) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                UPDATE auth_session
                SET revoked_at = COALESCE(revoked_at, ?), revoke_reason = COALESCE(revoke_reason, 'PASSWORD_CHANGED')
                WHERE user_id = ? AND id <> ?
                """,
                now,
                userId,
                currentSessionId
        );
        jdbcTemplate.update(
                """
                UPDATE auth_refresh_token t
                JOIN auth_session s ON s.id = t.session_id
                SET t.revoked_at = COALESCE(t.revoked_at, ?)
                WHERE s.user_id = ? AND s.id <> ?
                """,
                now,
                userId,
                currentSessionId
        );
        jdbcTemplate.update(
                "UPDATE auth_session SET auth_version = ?, last_used_at = ? WHERE id = ? AND user_id = ?",
                authVersion,
                now,
                currentSessionId,
                userId
        );
    }

    public SessionInfo currentSession(Long sessionId, Long userId) {
        List<SessionInfo> sessions = jdbcTemplate.query(
                """
                SELECT created_at, last_used_at, expires_at
                FROM auth_session
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL
                """,
                (resultSet, rowNum) -> new SessionInfo(
                        localDateTime(resultSet, "created_at"),
                        localDateTime(resultSet, "last_used_at"),
                        localDateTime(resultSet, "expires_at")
                ),
                sessionId,
                userId
        );
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    private void revokeSession(long sessionId, String reason, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE auth_session SET revoked_at = COALESCE(revoked_at, ?), revoke_reason = ? WHERE id = ?",
                now,
                reason,
                sessionId
        );
        jdbcTemplate.update(
                "UPDATE auth_refresh_token SET revoked_at = COALESCE(revoked_at, ?) WHERE session_id = ?",
                now,
                sessionId
        );
    }

    private RefreshRow mapRefreshRow(ResultSet resultSet) throws SQLException {
        return new RefreshRow(
                resultSet.getLong("token_id"),
                resultSet.getLong("session_id"),
                localDateTime(resultSet, "token_expires_at"),
                nullableLocalDateTime(resultSet, "used_at"),
                nullableLocalDateTime(resultSet, "token_revoked_at"),
                resultSet.getLong("user_id"),
                resultSet.getInt("session_auth_version"),
                localDateTime(resultSet, "session_created_at"),
                localDateTime(resultSet, "last_used_at"),
                localDateTime(resultSet, "session_expires_at"),
                nullableLocalDateTime(resultSet, "session_revoked_at"),
                resultSet.getString("username"),
                resultSet.getString("role"),
                resultSet.getString("status"),
                resultSet.getInt("user_auth_version"),
                nullableLocalDateTime(resultSet, "last_login_at")
        );
    }

    private static LocalDateTime localDateTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toLocalDateTime();
    }

    private static LocalDateTime nullableLocalDateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String role(String value) {
        return value == null || value.isBlank() ? "USER" : value;
    }

    public record SessionGrant(
            long sessionId,
            int authVersion,
            Long userId,
            String username,
            String role,
            String status,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            LocalDateTime lastUsedAt,
            LocalDateTime expiresAt,
            String refreshToken
    ) {
    }

    public record RotationResult(boolean succeeded, SessionGrant grant, String reason) {
        static RotationResult success(SessionGrant grant) {
            return new RotationResult(true, grant, "");
        }

        static RotationResult failure(String reason) {
            return new RotationResult(false, null, reason);
        }
    }

    public record SessionInfo(LocalDateTime createdAt, LocalDateTime lastUsedAt, LocalDateTime expiresAt) {
    }

    private record RefreshRow(
            long tokenId,
            long sessionId,
            LocalDateTime tokenExpiresAt,
            LocalDateTime usedAt,
            LocalDateTime tokenRevokedAt,
            Long userId,
            int sessionAuthVersion,
            LocalDateTime sessionCreatedAt,
            LocalDateTime lastUsedAt,
            LocalDateTime sessionExpiresAt,
            LocalDateTime sessionRevokedAt,
            String username,
            String role,
            String status,
            int userAuthVersion,
            LocalDateTime lastLoginAt
    ) {
    }
}
