package com.example.aiapp.agenttask.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public record AgentBudgetUsage(
        int completedSteps,
        int toolCalls,
        long modelTokens,
        long costMicros,
        long elapsedMillis,
        int retries
) {
    public static AgentBudgetUsage zero() {
        return new AgentBudgetUsage(0, 0, 0, 0, 0, 0);
    }

    public static AgentBudgetUsage from(Map<String, Object> values) {
        Map<String, Object> source = values == null ? Map.of() : values;
        return new AgentBudgetUsage(
                intValue(source, "completedSteps"),
                intValue(source, "toolCalls"),
                longValue(source, "modelTokens"),
                longValue(source, "costMicros"),
                longValue(source, "elapsedMillis"),
                intValue(source, "retries")
        );
    }

    public AgentBudgetUsage plus(int steps, int calls, long tokens, long cost, long elapsed, int retryCount) {
        return new AgentBudgetUsage(
                completedSteps + steps,
                toolCalls + calls,
                modelTokens + tokens,
                costMicros + cost,
                elapsedMillis + elapsed,
                retries + retryCount
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completedSteps", completedSteps);
        result.put("toolCalls", toolCalls);
        result.put("modelTokens", modelTokens);
        result.put("costMicros", costMicros);
        result.put("elapsedMillis", elapsedMillis);
        result.put("retries", retries);
        return result;
    }

    private static int intValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static long longValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
