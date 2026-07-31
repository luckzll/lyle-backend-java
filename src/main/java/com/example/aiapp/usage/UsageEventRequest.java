package com.example.aiapp.usage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;

public record UsageEventRequest(
        @NotBlank String eventId,
        @NotBlank String userId,
        String conversationId,
        String turnId,
        @NotBlank String traceId,
        String reservationId,
        @NotBlank String callType,
        @NotBlank String taskType,
        @NotBlank String provider,
        @NotBlank String model,
        String routeVersion,
        @PositiveOrZero long inputTokens,
        @PositiveOrZero long outputTokens,
        @PositiveOrZero long cacheReadTokens,
        @PositiveOrZero long cacheWriteTokens,
        @NotBlank String usageSource,
        @PositiveOrZero long estimatedCostMicros,
        @NotBlank String pricingVersion,
        String currency,
        @PositiveOrZero long latencyMillis,
        @NotBlank String status,
        String errorCategory,
        OffsetDateTime occurredAt
) {
}
