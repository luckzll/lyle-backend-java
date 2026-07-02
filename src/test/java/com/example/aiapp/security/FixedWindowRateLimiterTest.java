package com.example.aiapp.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    @Test
    void rejectsRequestsOverLimit() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneId.of("UTC"))
        );

        assertThat(limiter.allow("login:127.0.0.1", 2)).isTrue();
        assertThat(limiter.allow("login:127.0.0.1", 2)).isTrue();
        assertThat(limiter.allow("login:127.0.0.1", 2)).isFalse();
    }
}
