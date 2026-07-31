package com.example.aiapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        int authPerMinute,
        int chatPerMinute,
        int loginUsernamePerMinute,
        int registerPerMinute,
        int refreshPerMinute,
        int passwordPerMinute,
        String trustedProxyCidrs
) {
}
