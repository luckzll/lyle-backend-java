package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;
import java.util.Map;

public record ChatMessageVO(
        Long id,
        String conversationId,
        String turnId,
        String role,
        String content,
        Map<String, Object> metadata,
        LocalDateTime createdAt
) {
}
