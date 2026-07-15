package com.example.aiapp.agenttask.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public record AgentBudget(
        int maxSteps,
        int maxToolCalls,
        long maxModelTokens,
        long maxCostMicros,
        long maxWallTimeMillis,
        int maxRetriesPerStep,
        int maxConsecutiveNoProgress
) {
    public static final AgentBudget HARD_LIMIT = new AgentBudget(20, 40, 200_000, 10_000_000, 3_600_000, 3, 3);
    public static final AgentBudget DEFAULT = new AgentBudget(10, 20, 80_000, 2_000_000, 900_000, 2, 2);

    public static AgentBudget from(Map<String, Object> values) {
        Map<String, Object> source = values == null ? Map.of() : values;
        return new AgentBudget(
                intValue(source, "maxSteps", DEFAULT.maxSteps),
                intValue(source, "maxToolCalls", DEFAULT.maxToolCalls),
                longValue(source, "maxModelTokens", DEFAULT.maxModelTokens),
                longValue(source, "maxCostMicros", DEFAULT.maxCostMicros),
                longValue(source, "maxWallTimeMillis", DEFAULT.maxWallTimeMillis),
                intValue(source, "maxRetriesPerStep", DEFAULT.maxRetriesPerStep),
                intValue(source, "maxConsecutiveNoProgress", DEFAULT.maxConsecutiveNoProgress)
        ).withinHardLimit();
    }

    public AgentBudget withinHardLimit() {
        return new AgentBudget(
                positiveAtMost(maxSteps, HARD_LIMIT.maxSteps, "maxSteps"),
                positiveAtMost(maxToolCalls, HARD_LIMIT.maxToolCalls, "maxToolCalls"),
                positiveAtMost(maxModelTokens, HARD_LIMIT.maxModelTokens, "maxModelTokens"),
                positiveAtMost(maxCostMicros, HARD_LIMIT.maxCostMicros, "maxCostMicros"),
                positiveAtMost(maxWallTimeMillis, HARD_LIMIT.maxWallTimeMillis, "maxWallTimeMillis"),
                positiveAtMost(maxRetriesPerStep, HARD_LIMIT.maxRetriesPerStep, "maxRetriesPerStep"),
                positiveAtMost(maxConsecutiveNoProgress, HARD_LIMIT.maxConsecutiveNoProgress, "maxConsecutiveNoProgress")
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxSteps", maxSteps);
        result.put("maxToolCalls", maxToolCalls);
        result.put("maxModelTokens", maxModelTokens);
        result.put("maxCostMicros", maxCostMicros);
        result.put("maxWallTimeMillis", maxWallTimeMillis);
        result.put("maxRetriesPerStep", maxRetriesPerStep);
        result.put("maxConsecutiveNoProgress", maxConsecutiveNoProgress);
        return result;
    }

    private static int intValue(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long longValue(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int positiveAtMost(int value, int limit, String name) {
        if (value <= 0 || value > limit) throw new IllegalArgumentException(name + " 超出系统硬上限。");
        return value;
    }

    private static long positiveAtMost(long value, long limit, String name) {
        if (value <= 0 || value > limit) throw new IllegalArgumentException(name + " 超出系统硬上限。");
        return value;
    }
}
