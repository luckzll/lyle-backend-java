package com.example.aiapp.agenttask;

import com.example.aiapp.agenttask.domain.AgentBudget;
import com.example.aiapp.agenttask.config.AgentTaskFeatureFlags;
import com.example.aiapp.agenttask.domain.AgentBudgetUsage;
import com.example.aiapp.agenttask.domain.AgentTaskStatus;
import com.example.aiapp.agenttask.domain.budget.AgentBudgetGuard;
import com.example.aiapp.agenttask.domain.policy.AgentPolicyEngine;
import com.example.aiapp.agenttask.domain.policy.AgentConstraintPolicy;
import com.example.aiapp.agenttask.domain.state.AgentTaskStateMachine;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.PlanRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.PlanStepRecord;
import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.TaskRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTaskControlTest {

    @Test
    void stateMachineRejectsBoundaryBypassAndTerminalRestart() {
        AgentTaskStateMachine machine = new AgentTaskStateMachine();

        assertThat(machine.canTransition(AgentTaskStatus.AWAITING_PLAN_APPROVAL, AgentTaskStatus.READY)).isTrue();
        assertThat(machine.canTransition(AgentTaskStatus.RUNNING, AgentTaskStatus.AWAITING_CONFIRMATION)).isTrue();
        assertThat(machine.canTransition(AgentTaskStatus.AWAITING_CONFIRMATION, AgentTaskStatus.AWAITING_INPUT)).isTrue();
        assertThat(machine.canTransition(AgentTaskStatus.AWAITING_INPUT, AgentTaskStatus.AWAITING_PLAN_APPROVAL)).isTrue();
        assertThat(machine.canTransition(AgentTaskStatus.DRAFT, AgentTaskStatus.RUNNING)).isFalse();
        assertThat(machine.canTransition(AgentTaskStatus.COMPLETED, AgentTaskStatus.RUNNING)).isFalse();
        assertThatThrownBy(() -> machine.requireTransition(AgentTaskStatus.READY, AgentTaskStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void budgetStopsAtEveryHardDimensionAndWarnsAtEightyPercent() {
        AgentBudgetGuard guard = new AgentBudgetGuard();
        AgentBudget limit = AgentBudget.DEFAULT;

        AgentBudgetGuard.BudgetDecision warning = guard.check(
                limit,
                new AgentBudgetUsage(8, 0, 0, 0, 0, 0),
                0
        );
        assertThat(warning.allowed()).isTrue();
        assertThat(warning.nearLimit()).isTrue();

        AgentBudgetGuard.BudgetDecision stopped = guard.check(
                limit,
                new AgentBudgetUsage(1, limit.maxToolCalls(), 0, 0, 0, 0),
                0
        );
        assertThat(stopped.allowed()).isFalse();
        assertThat(stopped.exhaustedDimensions()).containsExactly("maxToolCalls");

        assertThatThrownBy(() -> AgentBudget.from(Map.of("maxSteps", AgentBudget.HARD_LIMIT.maxSteps() + 1)))
                .isInstanceOf(IllegalArgumentException.class);

        AgentBudgetUsage restoredUsage = AgentBudgetUsage.from(Map.of(
                "completedSteps", "3", "toolCalls", "4", "modelTokens", "500",
                "costMicros", "600", "elapsedMillis", "700", "retries", "1"));
        assertThat(restoredUsage).isEqualTo(new AgentBudgetUsage(3, 4, 500, 600, 700, 1));
        AgentBudget restoredBudget = AgentBudget.from(Map.of(
                "maxSteps", "9", "maxToolCalls", "12", "maxModelTokens", "70000",
                "maxCostMicros", "1500000", "maxWallTimeMillis", "800000",
                "maxRetriesPerStep", "2", "maxConsecutiveNoProgress", "2"));
        assertThat(restoredBudget.maxWallTimeMillis()).isEqualTo(800000);
        assertThat(restoredBudget.maxModelTokens()).isEqualTo(70000);
    }

    @Test
    void policyRequiresConfirmationAndFrozenToolSnapshot() {
        AgentPolicyEngine policy = new AgentPolicyEngine();
        Map<String, Object> readSnapshot = Map.of("allowedTools", List.of("project.inspect"));

        assertThat(policy.evaluate("project.inspect", "READ", false, readSnapshot, Map.of()).allowed()).isTrue();
        assertThat(policy.evaluate("research.search", "EXTERNAL_READ", false, readSnapshot, Map.of()).allowed()).isFalse();

        AgentPolicyEngine.PolicyDecision write = policy.evaluate(
                "todo.propose",
                "WRITE_LOW",
                true,
                Map.of("allowedTools", List.of("todo.propose")),
                Map.of("title", "确认后创建")
        );
        assertThat(write.allowed()).isTrue();
        assertThat(write.requiresConfirmation()).isTrue();

        assertThat(policy.evaluate(
                "todo.propose",
                "WRITE_LOW",
                true,
                Map.of("allowedTools", List.of("todo.propose")),
                Map.of("secretValue", "must-not-pass")
        ).allowed()).isFalse();

        assertThat(policy.evaluate(
                "workspace.git_status", "READ", false,
                Map.of("allowedTools", List.of("workspace.git_status")), Map.of("workspaceId", 9)
        ).allowed()).isTrue();
    }

    @Test
    void userForbiddenConstraintsAndProjectScopeAreDeterministicPolicyInputs() {
        Map<String, Object> forbidden = Map.of("forbidden", List.of("不访问外网", "不创建任务"));

        assertThat(AgentConstraintPolicy.violation("research.search", Map.of(), forbidden, null)).isPresent();
        assertThat(AgentConstraintPolicy.violation("todo.propose", Map.of(), forbidden, null)).isPresent();
        assertThat(AgentConstraintPolicy.violation("analysis.synthesize", Map.of(), forbidden, null)).isEmpty();
        assertThat(AgentConstraintPolicy.violation(
                "project.inspect", Map.of("projectId", 9), Map.of(), 9L)).isEmpty();
        assertThat(AgentConstraintPolicy.violation(
                "project.inspect", Map.of("projectId", 10), Map.of(), 9L)).isPresent();
        assertThat(AgentConstraintPolicy.violation(
                "project.inspect", Map.of(), Map.of(), null)).isPresent();
    }

    @Test
    void featureFlagsDefaultToExplicitTemplateToolUserAndModelAllowLists() {
        LocalDateTime now = LocalDateTime.now();
        TaskRecord task = new TaskRecord(1, 7, null, null, "检查", "检查项目", List.of("有证据"),
                Map.of(), "release-check", AgentTaskStatus.READY, "READ", 1, 1, 10L, 0, now, now);
        PlanStepRecord step = new PlanStepRecord(2, 3, "release", 1, "TOOL", "测试", "运行测试",
                "release.check", Map.of(), List.of(), "READ", Map.of(), false);
        PlanRecord plan = new PlanRecord(3, 1, 1, "MODEL", "检查项目", List.of("有证据"), Map.of(),
                Map.of(), AgentBudget.DEFAULT, Map.of(), "PENDING", null, null, now, List.of(step));

        assertThat(new AgentTaskFeatureFlags(true, "release-check", "release.check", "7", "default")
                .allows(task, plan, Map.of())).isTrue();
        assertThat(new AgentTaskFeatureFlags(false, "*", "*", "*", "*").allows(task, plan, Map.of())).isFalse();
        assertThat(new AgentTaskFeatureFlags(true, "", "*", "7", "default").allows(task, plan, Map.of())).isFalse();
        assertThat(new AgentTaskFeatureFlags(true, "release-check", "", "7", "default").allows(task, plan, Map.of())).isFalse();
        assertThat(new AgentTaskFeatureFlags(true, "release-check", "release.check", "", "default").allows(task, plan, Map.of())).isFalse();
        assertThat(new AgentTaskFeatureFlags(true, "release-check", "release.check", "7", "other")
                .allows(task, plan, Map.of("model", "model-x"))).isFalse();
    }
}
