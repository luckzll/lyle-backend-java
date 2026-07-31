package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public record AuthSessionVO(
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt,
        LocalDateTime expiresAt,
        boolean current
) {
}
