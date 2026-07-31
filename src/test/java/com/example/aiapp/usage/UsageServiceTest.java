package com.example.aiapp.usage;

import com.example.aiapp.config.UsageProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UsageServiceTest {

    @Test
    void returnsPerEventRejectionWithoutTouchingTheLedger() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UsageService service = new UsageService(
                jdbcTemplate,
                new UsageProperties(true, false, true, "internal", 10)
        );
        UsageEventRequest invalid = event("event-1", "UNKNOWN_CALL", 10);

        UsageBatchResponse response = service.acceptBatch(new UsageBatchRequest(List.of(invalid)));

        assertThat(response.accepted()).isZero();
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.rejectedEvents().get(0).reason()).isEqualTo("INVALID_CALL_TYPE");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void recordsAValidEventAndReportsDatabaseDuplicates() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        UsageService service = new UsageService(
                jdbcTemplate,
                new UsageProperties(true, false, true, "internal", 10)
        );

        UsageBatchResponse accepted = service.acceptBatch(
                new UsageBatchRequest(List.of(event("event-accepted", "LLM_TEXT", 10)))
        );
        UsageBatchResponse duplicate = service.acceptBatch(
                new UsageBatchRequest(List.of(event("event-duplicate", "LLM_TEXT", 10)))
        );

        assertThat(accepted.acceptedEventIds()).containsExactly("event-accepted");
        assertThat(duplicate.duplicateEventIds()).containsExactly("event-duplicate");
    }

    @Test
    void rejectsNegativeUsageValuesAsAnEventDetail() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UsageService service = new UsageService(
                jdbcTemplate,
                new UsageProperties(true, false, true, "internal", 10)
        );

        UsageBatchResponse response = service.acceptBatch(
                new UsageBatchRequest(List.of(event("event-negative", "LLM_TEXT", -1)))
        );

        assertThat(response.rejectedEvents().get(0).reason()).isEqualTo("NEGATIVE_USAGE_VALUE");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void activeReservationsAreIncludedBeforeEnforcingTheDailyHardLimit() {
        JdbcTemplate jdbcTemplate = quotaJdbc(
                60,
                20,
                50,
                20,
                200,
                200
        );
        UsageService service = new UsageService(
                jdbcTemplate,
                new UsageProperties(true, true, true, "internal", 10)
        );

        assertThatThrownBy(() -> service.reserve(42L, "conversation-1", "turn-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");

        verify(jdbcTemplate).queryForMap(
                contains("FROM ai_usage_reservation"),
                any(Object[].class)
        );
    }

    @Test
    void reservationSettlementCannotCrossUserBoundaries() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        UsageService service = new UsageService(
                jdbcTemplate,
                new UsageProperties(true, true, true, "internal", 10)
        );

        UsageBatchResponse response = service.acceptBatch(
                new UsageBatchRequest(List.of(eventWithReservation("event-settle", "reservation-1")))
        );

        assertThat(response.acceptedEventIds()).containsExactly("event-settle");
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
                contains("WHERE reservation_id = ? AND user_id = ? AND status = 'ACTIVE'"),
                arguments.capture()
        );
        assertThat(arguments.getValue()).endsWith("reservation-1", 42L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void recentTurnsUsesTheRequestedWindowAndAuthenticatedUser() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        UsageService service = new UsageService(
                jdbcTemplate,
                new UsageProperties(true, true, true, "internal", 10)
        );

        assertThat(service.recentTurns(84L, "month", 200)).isEmpty();

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(
                contains("e.user_id = ? AND e.occurred_at >= ? AND e.occurred_at < ?"),
                any(RowMapper.class),
                arguments.capture()
        );
        assertThat(arguments.getValue()[0]).isEqualTo(84L);
        assertThat(arguments.getValue()[3]).isEqualTo(50);
    }

    private static UsageEventRequest event(String eventId, String callType, long inputTokens) {
        return new UsageEventRequest(
                eventId,
                "42",
                "100",
                "turn-1",
                "trace-1",
                null,
                callType,
                "CHAT",
                "provider-a",
                "model-a",
                "v6.4-1",
                inputTokens,
                5,
                0,
                0,
                "PROVIDER_REPORTED",
                20,
                "2026-07-23",
                "CNY",
                100,
                "SUCCEEDED",
                null,
                OffsetDateTime.now()
        );
    }

    private static UsageEventRequest eventWithReservation(String eventId, String reservationId) {
        UsageEventRequest base = event(eventId, "LLM_TEXT", 10);
        return new UsageEventRequest(
                base.eventId(),
                base.userId(),
                base.conversationId(),
                base.turnId(),
                base.traceId(),
                reservationId,
                base.callType(),
                base.taskType(),
                base.provider(),
                base.model(),
                base.routeVersion(),
                base.inputTokens(),
                base.outputTokens(),
                base.cacheReadTokens(),
                base.cacheWriteTokens(),
                base.usageSource(),
                base.estimatedCostMicros(),
                base.pricingVersion(),
                base.currency(),
                base.latencyMillis(),
                base.status(),
                base.errorCategory(),
                base.occurredAt()
        );
    }

    private static JdbcTemplate quotaJdbc(
            long usedTokens,
            long usedCost,
            long activeTokens,
            long activeCost,
            long hardTokens,
            long hardCost
    ) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM ai_usage_policy")) {
                return Map.of(
                        "daily_hard_token_limit", hardTokens,
                        "daily_hard_cost_micros", hardCost,
                        "turn_max_calls", 12,
                        "turn_max_input_tokens", 60,
                        "turn_max_output_tokens", 40,
                        "turn_max_cost_micros", 50
                );
            }
            if (sql.contains("FROM ai_usage_reservation")) {
                return Map.of("tokens", activeTokens, "cost", activeCost);
            }
            return Map.of(
                    "input_tokens", usedTokens,
                    "output_tokens", 0,
                    "cache_read_tokens", 0,
                    "cache_write_tokens", 0,
                    "estimated_cost_micros", usedCost,
                    "call_count", 1,
                    "reported_calls", 1,
                    "estimated_calls", 0
            );
        });
        return jdbcTemplate;
    }
}
