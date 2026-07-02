package com.example.aiapp.security;

import com.example.aiapp.config.AuthProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    @Test
    void generateAndParseToken() {
        JwtService jwtService = new JwtService(new AuthProperties(
                "test-secret-key-that-is-long-enough-for-hmac",
                60
        ));

        String token = jwtService.generateToken(123L, "lyle", "ADMIN");
        Claims claims = jwtService.parse(token);

        assertThat(claims.getSubject()).isEqualTo("123");
        assertThat(claims.get("username", String.class)).isEqualTo("lyle");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }
}
