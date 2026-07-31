package com.example.aiapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        String jwtSecret,
        String jwtKeyId,
        String jwtPreviousSecret,
        String jwtPreviousKeyId,
        boolean acceptLegacyJwt,
        long jwtTtlMinutes,
        long refreshTtlDays,
        String issuer,
        String audience,
        String refreshCookieName,
        boolean secureCookie,
        boolean sessionV64Enabled,
        String allowedOrigins
) {
}
