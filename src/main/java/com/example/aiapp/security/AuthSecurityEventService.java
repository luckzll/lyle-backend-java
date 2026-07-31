package com.example.aiapp.security;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class AuthSecurityEventService {

    private static final Logger log = LoggerFactory.getLogger(AuthSecurityEventService.class);

    private final JdbcTemplate jdbcTemplate;

    public AuthSecurityEventService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String eventType,
            String outcome,
            String reasonCode,
            Long userId,
            String username,
            String remoteAddress
    ) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO auth_security_event (
                        id, user_id, event_type, outcome, reason_code,
                        username_hash, ip_hash, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    IdWorker.getId(),
                    userId,
                    bounded(eventType, 64),
                    bounded(outcome, 32),
                    bounded(reasonCode, 64),
                    digestNullable(username),
                    digestNullable(remoteAddress),
                    LocalDateTime.now()
            );
        } catch (RuntimeException exception) {
            // An unavailable audit sink must be visible operationally, but must
            // not turn a valid login or logout into a user-facing outage.
            log.warn("Failed to persist authentication security event. type={} outcome={}", eventType, outcome, exception);
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.substring(0, Math.min(maxLength, normalized.length()));
    }

    private static String digestNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
