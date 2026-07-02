package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public record KnowledgeBaseVO(
        Long id,
        String name,
        LocalDateTime createdAt
) {
}
