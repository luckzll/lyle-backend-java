package com.example.aiapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.qdrant")
public record QdrantProperties(
        boolean enabled,
        String endpoint
) {
}
