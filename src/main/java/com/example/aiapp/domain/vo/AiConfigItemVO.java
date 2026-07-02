package com.example.aiapp.domain.vo;

import java.time.LocalDateTime;

public record AiConfigItemVO(
        String key,
        String value,
        String description,
        LocalDateTime updatedAt
) {
}
