package com.example.aiapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.tos")
public record TosProperties(
        boolean enabled,
        String endpoint,
        String region,
        String accessKeyId,
        String secretAccessKey,
        String bucket,
        String savePathHead,
        String publicUrlHead
) {
}
