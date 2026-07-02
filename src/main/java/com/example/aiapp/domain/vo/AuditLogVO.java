package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public record AuditLogVO(
        Long id,
        Long userId,
        String action,
        String target,
        String targetId,
        LocalDateTime createdAt
) {
}
