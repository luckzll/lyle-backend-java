package com.example.aiapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.minio")
public record MinioProperties(
        boolean enabled,
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket
) {
}
