package com.example.aiapp.domain.dto;

public record UsageDTO(
        Integer promptTokens,
        Integer completionTokens
) {
}
