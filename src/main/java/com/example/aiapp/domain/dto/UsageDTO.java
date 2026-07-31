package com.example.aiapp.domain.dto;

public record UsageDTO(
        Integer promptTokens,
        Integer completionTokens,
        Integer callCount,
        Long estimatedCostMicros,
        String measurementSource
) {
    public UsageDTO(Integer promptTokens, Integer completionTokens) {
        this(promptTokens, completionTokens, 0, 0L, "PENDING");
    }
}
