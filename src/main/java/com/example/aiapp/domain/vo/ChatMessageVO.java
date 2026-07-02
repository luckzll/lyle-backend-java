package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public record ChatMessageVO(
        Long id,
        String conversationId,
        String role,
        String content,
        LocalDateTime createdAt
) {
}
