package com.example.aiapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.service")
public record AiServiceProperties(
        String baseUrl,
        String internalToken,
        Integer maxInMemorySize,
        Integer connectTimeoutMillis,
        Integer responseTimeoutSeconds
) {
}
