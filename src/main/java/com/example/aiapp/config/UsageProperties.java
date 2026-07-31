package com.example.aiapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.usage")
public record UsageProperties(
        boolean ledgerEnabled,
        boolean quotaEnforcementEnabled,
        boolean userViewEnabled,
        String internalToken,
        long reservationTtlMinutes
) {
}
