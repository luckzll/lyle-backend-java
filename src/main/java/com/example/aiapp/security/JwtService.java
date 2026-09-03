package com.example.aiapp.security;

import com.example.aiapp.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * JWT 签发与校验服务。
 */
@Component
public class JwtService {

    private final AuthProperties properties;
    private final SecretKey currentKey;
    private final SecretKey previousKey;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        this.currentKey = key(properties.jwtSecret());
        this.previousKey = hasText(properties.jwtPreviousSecret())
                ? key(properties.jwtPreviousSecret())
                : null;
    }

    public String generateToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, 0L, 1);
    }

    public String generateToken(Long userId, String username, String role, Long sessionId, int authVersion) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.jwtTtlMinutes() * 60);
        return Jwts.builder()
                .header()
                .keyId(properties.jwtKeyId())
                .and()
                .subject(String.valueOf(userId))
                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()
                .claim("username", username)
                .claim("role", role)
                .claim("sid", String.valueOf(sessionId))
                .claim("ver", authVersion)
                .claim("typ", "access")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(currentKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .keyLocator(this::locateVerificationKey)
                .requireIssuer(properties.issuer())
                .requireAudience(properties.audience())
                .require("typ", "access")
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long expiresInSeconds() {
        return properties.jwtTtlMinutes() * 60;
    }

    private Key locateVerificationKey(io.jsonwebtoken.Header header) {
        String keyId = header instanceof ProtectedHeader protectedHeader
                ? protectedHeader.getKeyId()
                : null;
        if (Objects.equals(properties.jwtKeyId(), keyId)) {
            return currentKey;
        }
        if (previousKey != null && Objects.equals(properties.jwtPreviousKeyId(), keyId)) {
            return previousKey;
        }
        if ((keyId == null || keyId.isBlank()) && properties.acceptLegacyJwt()) {
            return currentKey;
        }
        throw new JwtException("Unknown or missing JWT key id.");
    }

    private static SecretKey key(String value) {
        return Keys.hmacShaKeyFor(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
