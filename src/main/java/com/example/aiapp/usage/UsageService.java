package com.example.aiapp.usage;

import com.example.aiapp.billing.BillingService;
import com.example.aiapp.config.UsageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UsageService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DEFAULT_DAILY_SOFT_TOKENS = 200_000L;
    private static final long DEFAULT_DAILY_HARD_TOKENS = 250_000L;
    private static final long DEFAULT_DAILY_SOFT_COST = 800_000L;
    private static final long DEFAULT_DAILY_HARD_COST = 1_000_000L;
    private static final Set<String> CALL_TYPES = Set.of(
            "LLM_TEXT", "LLM_VISION", "EMBEDDING", "RERANK", "IMAGE_GENERATION"
    );
    private static final Set<String> TASK_TYPES = Set.of(
            "CHAT", "WRITER", "INTENT", "SUPERVISOR", "ROUTER",
            "MEMORY", "MEMORY_EXTRACT", "SUMMARY", "JUDGE", "REVIEWER",
            "REWRITE", "TOOL", "EMBEDDING", "RERANK",
            "IMAGE_ANALYZE", "IMAGE_GENERATE", "IMAGE_EDIT"
    );
    private static final Set<String> USAGE_SOURCES = Set.of(
            "PROVIDER_REPORTED", "ESTIMATED", "PARTIAL_ESTIMATE"
    );
    private static final Set<String> STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final Pattern DIMENSION_VALUE = Pattern.compile("[A-Z0-9][A-Z0-9._:/-]*");

    private final JdbcTemplate jdbcTemplate;
    private final UsageProperties properties;
    private final BillingService billingService;

    public UsageService(JdbcTemplate jdbcTemplate, UsageProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public UsageService(
            JdbcTemplate jdbcTemplate,
            UsageProperties properties,
            BillingService billingService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.billingService = billingService;
    }

    @Transactional
    public UsageBatchResponse acceptBatch(UsageBatchRequest request) {
        List<String> accepted = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        List<UsageBatchResponse.RejectedEvent> rejected = new ArrayList<>();
        if (!properties.ledgerEnabled()) {
            for (UsageEventRequest event : request.events()) {
                rejected.add(new UsageBatchResponse.RejectedEvent(event.eventId(), "LEDGER_DISABLED"));
            }
            return new UsageBatchResponse(0, 0, rejected.size(), accepted, duplicates, rejected);
        }

        for (UsageEventRequest event : request.events()) {
            try {
                validateEvent(event);
                long userId = Long.parseLong(event.userId());
                LocalDateTime occurredAt = occurredAt(event.occurredAt());
                LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
                if (occurredAt.isBefore(now.minusDays(7))
                        || occurredAt.isAfter(now.plusMinutes(10))) {
                    rejected.add(new UsageBatchResponse.RejectedEvent(event.eventId(), "OCCURRED_AT_OUT_OF_RANGE"));
                    continue;
                }
                int inserted = jdbcTemplate.update(
                        """
                        INSERT IGNORE INTO ai_usage_event (
                            event_id, user_id, conversation_id, turn_id, trace_id, reservation_id,
                            call_type, task_type, provider, model, route_version,
                            input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                            usage_source, estimated_cost_micros, pricing_version, currency,
                            latency_millis, status, error_category, occurred_at, received_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        event.eventId(),
                        userId,
                        blankToNull(event.conversationId()),
                        blankToNull(event.turnId()),
                        event.traceId(),
                        blankToNull(event.reservationId()),
                        normalized(event.callType(), 32),
                        normalized(event.taskType(), 64),
                        normalized(event.provider(), 64),
                        normalized(event.model(), 128),
                        normalizedNullable(event.routeVersion(), 64),
                        event.inputTokens(),
                        event.outputTokens(),
                        event.cacheReadTokens(),
                        event.cacheWriteTokens(),
                        normalized(event.usageSource(), 32),
                        event.estimatedCostMicros(),
                        normalized(event.pricingVersion(), 64),
                        normalized(defaultString(event.currency(), "CNY"), 8),
                        event.latencyMillis(),
                        normalized(event.status(), 32),
                        normalizedNullable(event.errorCategory(), 64),
                        occurredAt,
                        LocalDateTime.now()
                );
                if (inserted == 0) {
                    duplicates.add(event.eventId());
                    continue;
                }
                accepted.add(event.eventId());
                settleReservation(event);
                if (billingService != null) {
                    settleCommercialAfterUsageCommit(event);
                }
            } catch (NumberFormatException exception) {
                rejected.add(new UsageBatchResponse.RejectedEvent(eventIdOf(event), "INVALID_USER_ID"));
            } catch (IllegalArgumentException exception) {
                rejected.add(new UsageBatchResponse.RejectedEvent(eventIdOf(event), exception.getMessage()));
            }
        }
        return new UsageBatchResponse(
                accepted.size(),
                duplicates.size(),
                rejected.size(),
                accepted,
                duplicates,
                rejected
        );
    }

    public Map<String, Object> summary(Long userId, String period) {
        Window window = window(period);
        Map<String, Object> totals = jdbcTemplate.queryForMap(
                """
                SELECT
                    COALESCE(SUM(input_tokens), 0) AS input_tokens,
                    COALESCE(SUM(output_tokens), 0) AS output_tokens,
                    COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens,
                    COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens,
                    COALESCE(SUM(estimated_cost_micros), 0) AS estimated_cost_micros,
                    COUNT(*) AS call_count,
                    COALESCE(SUM(CASE WHEN usage_source = 'PROVIDER_REPORTED' THEN 1 ELSE 0 END), 0) AS reported_calls,
                    COALESCE(SUM(CASE WHEN usage_source <> 'PROVIDER_REPORTED' THEN 1 ELSE 0 END), 0) AS estimated_calls
                FROM ai_usage_event
                WHERE user_id = ? AND occurred_at >= ? AND occurred_at < ?
                """,
                userId,
                window.start(),
                window.end()
        );
        long input = number(totals.get("input_tokens"));
        long output = number(totals.get("output_tokens"));
        long cacheRead = number(totals.get("cache_read_tokens"));
        long cacheWrite = number(totals.get("cache_write_tokens"));
        long calls = number(totals.get("call_count"));
        long reported = number(totals.get("reported_calls"));
        long estimated = number(totals.get("estimated_calls"));
        long totalTokens = input + output + cacheRead + cacheWrite;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", window.period());
        result.put("periodStart", window.start());
        result.put("periodEnd", window.end());
        result.put("totalTokens", totalTokens);
        result.put("inputTokens", input);
        result.put("outputTokens", output);
        result.put("cacheReadTokens", cacheRead);
        result.put("cacheWriteTokens", cacheWrite);
        result.put("callCount", calls);
        result.put("estimatedCostMicros", number(totals.get("estimated_cost_micros")));
        result.put("reportedRatio", calls == 0 ? 0 : Math.round(reported * 1000.0 / calls) / 10.0);
        result.put("estimatedRatio", calls == 0 ? 0 : Math.round(estimated * 1000.0 / calls) / 10.0);
        result.put("measurementStatus", calls == 0 ? "EMPTY" : estimated == 0 ? "REPORTED" : reported == 0 ? "ESTIMATED" : "PARTIAL_ESTIMATE");
        return result;
    }

    public List<Map<String, Object>> byTask(Long userId, String period) {
        Window window = window(period);
        return jdbcTemplate.query(
                """
                SELECT task_type,
                       SUM(input_tokens + output_tokens + cache_read_tokens + cache_write_tokens) AS total_tokens,
                       SUM(estimated_cost_micros) AS estimated_cost_micros,
                       COUNT(*) AS call_count
                FROM ai_usage_event
                WHERE user_id = ? AND occurred_at >= ? AND occurred_at < ?
                GROUP BY task_type
                ORDER BY total_tokens DESC
                """,
                (resultSet, rowNum) -> {
                    String taskType = resultSet.getString("task_type");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("taskType", taskType);
                    item.put("label", taskLabel(taskType));
                    item.put("description", taskDescription(taskType));
                    item.put("totalTokens", resultSet.getLong("total_tokens"));
                    item.put("estimatedCostMicros", resultSet.getLong("estimated_cost_micros"));
                    item.put("callCount", resultSet.getLong("call_count"));
                    return item;
                },
                userId,
                window.start(),
                window.end()
        );
    }

    public List<Map<String, Object>> trend(Long userId, String period) {
        Window window = window(period);
        String bucket = "today".equals(window.period())
                ? "DATE_FORMAT(occurred_at, '%H:00')"
                : "DATE_FORMAT(occurred_at, '%m-%d')";
        return jdbcTemplate.query(
                """
                SELECT %s AS bucket,
                       SUM(input_tokens + output_tokens + cache_read_tokens + cache_write_tokens) AS total_tokens,
                       COUNT(*) AS call_count
                FROM ai_usage_event
                WHERE user_id = ? AND occurred_at >= ? AND occurred_at < ?
                GROUP BY bucket
                ORDER BY MIN(occurred_at)
                """.formatted(bucket),
                (resultSet, rowNum) -> Map.<String, Object>of(
                        "bucket", resultSet.getString("bucket"),
                        "totalTokens", resultSet.getLong("total_tokens"),
                        "callCount", resultSet.getLong("call_count")
                ),
                userId,
                window.start(),
                window.end()
        );
    }

    public List<Map<String, Object>> recentTurns(Long userId, String period, int limit) {
        Window window = window(period);
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        return jdbcTemplate.query(
                """
                SELECT
                    COALESCE(NULLIF(e.turn_id, ''), e.trace_id) AS usage_turn_id,
                    MAX(e.conversation_id) AS conversation_id,
                    MAX(c.title) AS conversation_title,
                    MAX(e.task_type) AS task_type,
                    SUM(e.input_tokens + e.output_tokens + e.cache_read_tokens + e.cache_write_tokens) AS total_tokens,
                    SUM(e.estimated_cost_micros) AS estimated_cost_micros,
                    COUNT(*) AS call_count,
                    SUM(CASE WHEN e.usage_source = 'PROVIDER_REPORTED' THEN 1 ELSE 0 END) AS reported_calls,
                    MAX(e.occurred_at) AS occurred_at
                FROM ai_usage_event e
                LEFT JOIN conversation c ON c.id = e.conversation_id AND c.user_id = e.user_id
                WHERE e.user_id = ? AND e.occurred_at >= ? AND e.occurred_at < ?
                GROUP BY usage_turn_id
                ORDER BY occurred_at DESC
                LIMIT ?
                """,
                (resultSet, rowNum) -> {
                    long calls = resultSet.getLong("call_count");
                    long reportedCalls = resultSet.getLong("reported_calls");
                    String taskType = resultSet.getString("task_type");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("turnId", resultSet.getString("usage_turn_id"));
                    item.put("conversationId", resultSet.getString("conversation_id"));
                    item.put("workTitle", defaultString(resultSet.getString("conversation_title"), "AI 工作"));
                    item.put("taskType", taskType);
                    item.put("taskLabel", taskLabel(taskType));
                    item.put("totalTokens", resultSet.getLong("total_tokens"));
                    item.put("estimatedCostMicros", resultSet.getLong("estimated_cost_micros"));
                    item.put("callCount", calls);
                    item.put("measurementSource", calls == reportedCalls ? "PROVIDER_REPORTED" : reportedCalls == 0 ? "ESTIMATED" : "PARTIAL_ESTIMATE");
                    item.put("occurredAt", resultSet.getTimestamp("occurred_at").toLocalDateTime());
                    return item;
                },
                userId,
                window.start(),
                window.end(),
                boundedLimit
        );
    }

    public Map<String, Object> policy(Long userId) {
        ensurePolicy(userId);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM ai_usage_policy WHERE user_id = ?",
                userId
        );
        Map<String, Object> today = summary(userId, "today");
        long usedTokens = number(today.get("totalTokens"));
        long usedCost = number(today.get("estimatedCostMicros"));
        long hardTokens = number(row.get("daily_hard_token_limit"));
        long hardCost = number(row.get("daily_hard_cost_micros"));
        long softTokens = number(row.get("daily_soft_token_limit"));
        double tokenRatio = hardTokens == 0 ? 0 : usedTokens * 100.0 / hardTokens;
        double costRatio = hardCost == 0 ? 0 : usedCost * 100.0 / hardCost;
        double ratio = Math.max(tokenRatio, costRatio);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dailySoftTokenLimit", softTokens);
        result.put("dailyHardTokenLimit", hardTokens);
        result.put("dailySoftCostMicros", number(row.get("daily_soft_cost_micros")));
        result.put("dailyHardCostMicros", hardCost);
        result.put("turnMaxCalls", number(row.get("turn_max_calls")));
        result.put("turnMaxInputTokens", number(row.get("turn_max_input_tokens")));
        result.put("turnMaxOutputTokens", number(row.get("turn_max_output_tokens")));
        result.put("turnMaxCostMicros", number(row.get("turn_max_cost_micros")));
        result.put("usedTokens", usedTokens);
        result.put("usedCostMicros", usedCost);
        result.put("usedPercent", Math.round(ratio * 10.0) / 10.0);
        result.put("remainingTokens", Math.max(0, hardTokens - usedTokens));
        result.put("status", ratio >= 100 ? "EXHAUSTED" : ratio >= 80 || usedTokens >= softTokens ? "NEAR_LIMIT" : "HEALTHY");
        result.put("resetsAt", LocalDate.now(BUSINESS_ZONE).plusDays(1).atStartOfDay());
        return result;
    }

    @Transactional
    public Map<String, Object> updatePolicy(Long userId, Map<String, Long> request) {
        long softTokens = positive(request.get("dailySoftTokenLimit"), DEFAULT_DAILY_SOFT_TOKENS);
        long hardTokens = positive(request.get("dailyHardTokenLimit"), DEFAULT_DAILY_HARD_TOKENS);
        long softCost = positive(request.get("dailySoftCostMicros"), DEFAULT_DAILY_SOFT_COST);
        long hardCost = positive(request.get("dailyHardCostMicros"), DEFAULT_DAILY_HARD_COST);
        if (softTokens > hardTokens || softCost > hardCost) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "软上限不能高于硬上限。");
        }
        ensurePolicy(userId);
        jdbcTemplate.update(
                """
                UPDATE ai_usage_policy
                SET daily_soft_token_limit = ?, daily_hard_token_limit = ?,
                    daily_soft_cost_micros = ?, daily_hard_cost_micros = ?, updated_at = ?
                WHERE user_id = ?
                """,
                softTokens,
                hardTokens,
                softCost,
                hardCost,
                LocalDateTime.now(),
                userId
        );
        return policy(userId);
    }

    @Transactional
    public Reservation reserve(Long userId, String conversationId, String turnId) {
        ensurePolicy(userId);
        Map<String, Object> policy = jdbcTemplate.queryForMap(
                "SELECT * FROM ai_usage_policy WHERE user_id = ? FOR UPDATE",
                userId
        );
        Map<String, Object> today = summary(userId, "today");
        Map<String, Object> active = jdbcTemplate.queryForMap(
                """
                SELECT
                    COALESCE(SUM(reserved_tokens - settled_tokens), 0) AS tokens,
                    COALESCE(SUM(reserved_cost_micros - settled_cost_micros), 0) AS cost
                FROM ai_usage_reservation
                WHERE user_id = ? AND status = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP(6)
                """,
                userId
        );
        long reservedTokens = number(policy.get("turn_max_input_tokens")) + number(policy.get("turn_max_output_tokens"));
        long reservedCost = number(policy.get("turn_max_cost_micros"));
        long projectedTokens = number(today.get("totalTokens")) + number(active.get("tokens")) + reservedTokens;
        long projectedCost = number(today.get("estimatedCostMicros")) + number(active.get("cost")) + reservedCost;
        if (properties.quotaEnforcementEnabled()
                && (projectedTokens > number(policy.get("daily_hard_token_limit"))
                || projectedCost > number(policy.get("daily_hard_cost_micros")))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日 AI 用量已达上限，额度将在明日 00:00 恢复。");
        }
        String reservationId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO ai_usage_reservation (
                    reservation_id, user_id, conversation_id, turn_id, trace_id,
                    reserved_tokens, reserved_cost_micros, reserved_calls,
                    status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                reservationId,
                userId,
                blankToNull(conversationId),
                blankToNull(turnId),
                traceId,
                reservedTokens,
                reservedCost,
                number(policy.get("turn_max_calls")),
                now,
                now.plusMinutes(Math.max(1, properties.reservationTtlMinutes()))
        );
        return new Reservation(
                reservationId,
                traceId,
                number(policy.get("turn_max_calls")),
                number(policy.get("turn_max_input_tokens")),
                number(policy.get("turn_max_output_tokens")),
                reservedCost
        );
    }

    @Transactional
    public void releaseReservation(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE ai_usage_reservation
                SET status = 'RELEASED', released_at = ?
                WHERE reservation_id = ? AND status = 'ACTIVE'
                """,
                LocalDateTime.now(),
                reservationId
        );
    }

    private void settleReservation(UsageEventRequest event) {
        if (event.reservationId() == null || event.reservationId().isBlank()) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE ai_usage_reservation
                SET settled_tokens = settled_tokens + ?,
                    settled_cost_micros = settled_cost_micros + ?,
                    settled_calls = settled_calls + 1
                WHERE reservation_id = ? AND user_id = ? AND status = 'ACTIVE'
                """,
                event.inputTokens() + event.outputTokens() + event.cacheReadTokens() + event.cacheWriteTokens(),
                event.estimatedCostMicros(),
                event.reservationId(),
                Long.parseLong(event.userId())
        );
    }

    private void settleCommercialAfterUsageCommit(UsageEventRequest event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            billingService.settleUsage(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    billingService.settleUsage(event);
                } catch (RuntimeException ignored) {
                    // The immutable AI usage event has already committed. Commercial
                    // settlement is independently retryable by usageEventId and must
                    // never erase or reject the metering evidence.
                }
            }
        });
    }

    private void ensurePolicy(Long userId) {
        jdbcTemplate.update(
                """
                INSERT IGNORE INTO ai_usage_policy (
                    user_id, daily_soft_token_limit, daily_hard_token_limit,
                    daily_soft_cost_micros, daily_hard_cost_micros,
                    turn_max_calls, turn_max_input_tokens, turn_max_output_tokens,
                    turn_max_cost_micros, updated_at
                ) VALUES (?, ?, ?, ?, ?, 12, 100000, 16000, 200000, ?)
                """,
                userId,
                DEFAULT_DAILY_SOFT_TOKENS,
                DEFAULT_DAILY_HARD_TOKENS,
                DEFAULT_DAILY_SOFT_COST,
                DEFAULT_DAILY_HARD_COST,
                LocalDateTime.now()
        );
    }

    private static Window window(String value) {
        String period = "month".equalsIgnoreCase(value) ? "month" : "today";
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDateTime start = "month".equals(period)
                ? today.withDayOfMonth(1).atStartOfDay()
                : today.atStartOfDay();
        LocalDateTime end = "month".equals(period)
                ? today.plusMonths(1).withDayOfMonth(1).atStartOfDay()
                : today.plusDays(1).atStartOfDay();
        return new Window(period, start, end);
    }

    private static void validateEvent(UsageEventRequest event) {
        if (event == null) {
            throw new IllegalArgumentException("EVENT_REQUIRED");
        }
        requiredIdentifier(event.eventId(), 64, "INVALID_EVENT_ID");
        requiredIdentifier(event.userId(), 32, "INVALID_USER_ID");
        requiredIdentifier(event.traceId(), 128, "INVALID_TRACE_ID");
        optionalIdentifier(event.conversationId(), 128, "INVALID_CONVERSATION_ID");
        optionalIdentifier(event.turnId(), 128, "INVALID_TURN_ID");
        optionalIdentifier(event.reservationId(), 64, "INVALID_RESERVATION_ID");
        requireAllowed(event.callType(), 32, CALL_TYPES, "INVALID_CALL_TYPE");
        requireAllowed(event.taskType(), 64, TASK_TYPES, "INVALID_TASK_TYPE");
        requireAllowed(event.usageSource(), 32, USAGE_SOURCES, "INVALID_USAGE_SOURCE");
        requireAllowed(event.status(), 32, STATUSES, "INVALID_STATUS");
        String provider = normalized(event.provider(), 64);
        String model = normalized(event.model(), 128);
        if (!DIMENSION_VALUE.matcher(provider).matches() || !DIMENSION_VALUE.matcher(model).matches()) {
            throw new IllegalArgumentException("INVALID_MODEL_DIMENSION");
        }
        normalized(event.pricingVersion(), 64);
        normalized(defaultString(event.currency(), "CNY"), 8);
        normalizedNullable(event.routeVersion(), 64);
        normalizedNullable(event.errorCategory(), 64);
        if (event.inputTokens() < 0
                || event.outputTokens() < 0
                || event.cacheReadTokens() < 0
                || event.cacheWriteTokens() < 0
                || event.estimatedCostMicros() < 0
                || event.latencyMillis() < 0) {
            throw new IllegalArgumentException("NEGATIVE_USAGE_VALUE");
        }
    }

    private static void requireAllowed(String value, int maxLength, Set<String> allowed, String reason) {
        String normalized = normalized(value, maxLength);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(reason);
        }
    }

    private static void requiredIdentifier(String value, int maxLength, String reason) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(reason);
        }
    }

    private static void optionalIdentifier(String value, int maxLength, String reason) {
        if (value != null && !value.isBlank() && value.length() > maxLength) {
            throw new IllegalArgumentException(reason);
        }
    }

    private static String eventIdOf(UsageEventRequest event) {
        return event == null || event.eventId() == null ? "" : event.eventId();
    }

    private static LocalDateTime occurredAt(OffsetDateTime value) {
        return value == null
                ? LocalDateTime.now()
                : value.atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static long positive(Long value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static String normalized(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("REQUIRED_FIELD_MISSING");
        }
        String result = value.trim().toUpperCase(Locale.ROOT);
        if (result.length() > maxLength) {
            throw new IllegalArgumentException("FIELD_TOO_LONG");
        }
        return result;
    }

    private static String normalizedNullable(String value, int maxLength) {
        return value == null || value.isBlank() ? null : normalized(value, maxLength);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String taskLabel(String taskType) {
        if (taskType == null) return "其他 AI 调用";
        return switch (taskType.toUpperCase(Locale.ROOT)) {
            case "CHAT", "WRITER", "REWRITE" -> "对话回答";
            case "MEMORY", "MEMORY_EXTRACT", "SUMMARY" -> "记忆整理";
            case "REVIEWER", "JUDGE" -> "审核与重写";
            case "INTENT", "SUPERVISOR", "ROUTER" -> "意图识别";
            default -> "其他 AI 调用";
        };
    }

    private static String taskDescription(String taskType) {
        String label = taskLabel(taskType);
        return switch (label) {
            case "对话回答" -> "用于日常对话与问题回答";
            case "记忆整理" -> "用于信息整理与归档总结";
            case "审核与重写" -> "用于内容审核与优化重写";
            case "意图识别" -> "用于识别用户意图与任务分类";
            default -> "用于完成工作中的 AI 辅助步骤";
        };
    }

    public record Reservation(
            String reservationId,
            String traceId,
            long maxCalls,
            long maxInputTokens,
            long maxOutputTokens,
            long maxCostMicros
    ) {
    }

    private record Window(String period, LocalDateTime start, LocalDateTime end) {
    }
}
