package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public record ConversationVO(
        Long id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
