package com.example.aiapp.security;

import com.example.aiapp.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    @Test
    void generateAndParseToken() {
        JwtService jwtService = new JwtService(properties(
                "test-secret-key-that-is-long-enough-for-hmac",
                "current-key",
                "",
                ""
        ));

        String token = jwtService.generateToken(123L, "lyle", "ADMIN");
        Claims claims = jwtService.parse(token);

        assertThat(claims.getSubject()).isEqualTo("123");
        assertThat(claims.get("username", String.class)).isEqualTo("lyle");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void acceptsPreviousKeyDuringRotationWindow() {
        String previousSecret = "previous-test-secret-key-that-is-long-enough";
        JwtService previousIssuer = new JwtService(properties(
                previousSecret,
                "previous-key",
                "",
                ""
        ));
        JwtService rotatedVerifier = new JwtService(properties(
                "current-test-secret-key-that-is-long-enough",
                "current-key",
                previousSecret,
                "previous-key"
        ));

        Claims claims = rotatedVerifier.parse(
                previousIssuer.generateToken(321L, "rotating-user", "USER")
        );

        assertThat(claims.getSubject()).isEqualTo("321");
    }

    @Test
    void rejectsTokenWithUnknownKeyId() {
        JwtService unknownIssuer = new JwtService(properties(
                "unknown-test-secret-key-that-is-long-enough",
                "unknown-key",
                "",
                ""
        ));
        JwtService verifier = new JwtService(properties(
                "current-test-secret-key-that-is-long-enough",
                "current-key",
                "",
                ""
        ));

        assertThatThrownBy(() -> verifier.parse(
                unknownIssuer.generateToken(123L, "lyle", "USER")
        )).isInstanceOf(JwtException.class);
    }

    private static AuthProperties properties(
            String currentSecret,
            String currentKeyId,
            String previousSecret,
            String previousKeyId
    ) {
        return new AuthProperties(
                currentSecret,
                currentKeyId,
                previousSecret,
                previousKeyId,
                false,
                60,
                30,
                "lyle-ai",
                "lyle-web",
                "lyle_refresh",
                false,
                false,
                "http://127.0.0.1:4173"
        );
    }
}
