package com.example.aiapp.agenttask.domain.budget;

import com.example.aiapp.agenttask.domain.AgentBudget;
import com.example.aiapp.agenttask.domain.AgentBudgetUsage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentBudgetGuard {

    public BudgetDecision check(AgentBudget limit, AgentBudgetUsage used, int noProgressCount) {
        List<String> exhausted = new ArrayList<>();
        if (used.completedSteps() >= limit.maxSteps()) exhausted.add("maxSteps");
        if (used.toolCalls() >= limit.maxToolCalls()) exhausted.add("maxToolCalls");
        if (used.modelTokens() >= limit.maxModelTokens()) exhausted.add("maxModelTokens");
        if (used.costMicros() >= limit.maxCostMicros()) exhausted.add("maxCostMicros");
        if (used.elapsedMillis() >= limit.maxWallTimeMillis()) exhausted.add("maxWallTimeMillis");
        if (noProgressCount >= limit.maxConsecutiveNoProgress()) exhausted.add("maxConsecutiveNoProgress");
        return new BudgetDecision(exhausted.isEmpty(), usageRatio(limit, used), List.copyOf(exhausted));
    }

    public double usageRatio(AgentBudget limit, AgentBudgetUsage used) {
        double ratio = 0;
        ratio = Math.max(ratio, ratio(used.completedSteps(), limit.maxSteps()));
        ratio = Math.max(ratio, ratio(used.toolCalls(), limit.maxToolCalls()));
        ratio = Math.max(ratio, ratio(used.modelTokens(), limit.maxModelTokens()));
        ratio = Math.max(ratio, ratio(used.costMicros(), limit.maxCostMicros()));
        ratio = Math.max(ratio, ratio(used.elapsedMillis(), limit.maxWallTimeMillis()));
        return Math.min(1, ratio);
    }

    private static double ratio(long used, long limit) {
        return limit <= 0 ? 1 : (double) used / limit;
    }

    public record BudgetDecision(boolean allowed, double usageRatio, List<String> exhaustedDimensions) {
        public boolean nearLimit() {
            return allowed && usageRatio >= 0.8;
        }
    }
}
