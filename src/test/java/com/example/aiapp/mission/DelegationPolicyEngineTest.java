package com.example.aiapp.mission;

import com.example.aiapp.mission.application.DelegationPolicyEngine;
import com.example.aiapp.mission.application.DelegationPolicyEngine.ActionIntent;
import com.example.aiapp.mission.application.DelegationPolicyEngine.PolicyDecision;
import com.example.aiapp.mission.application.DelegationRuntimeGuard.RuntimeDecision;
import com.example.aiapp.mission.config.MissionFeatureFlags;
import com.example.aiapp.mission.infrastructure.MissionRepository.GrantRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationPolicyEngineTest {

    @Test
    void defaultDenyAndSystemRiskCannotBeExpandedByGrantText() {
        DelegationPolicyEngine engine = new DelegationPolicyEngine(flags(true));
        GrantRecord grant = grant(List.of(readRule()));

        PolicyDecision unknown = engine.evaluate(
                7L,
                grant,
                engine.normalize("unknown.write", "R0", false, Map.of())
        );
        PolicyDecision forbidden = engine.evaluate(
                7L,
                grant,
                engine.normalize("git.commit", "R0", true, Map.of())
        );

        assertThat(unknown.decision()).isEqualTo("DENY");
        assertThat(unknown.reasonCode()).isEqualTo("SYSTEM_RISK_FORBIDDEN");
        assertThat(forbidden.decision()).isEqualTo("DENY");
        assertThat(forbidden.reasonCode()).isEqualTo("SYSTEM_RISK_FORBIDDEN");
    }

    @Test
    void r1RequiresSpecificScopeCompensationAndFeatureFlag() {
        Map<String, Object> rule = todoRule();
        GrantRecord grant = grant(List.of(rule));
        ActionIntent action = new DelegationPolicyEngine(flags(true)).normalize(
                "todo.propose",
                "WRITE_LOW",
                true,
                Map.of("projectId", 9, "title", "跟进异常")
        );

        PolicyDecision disabled = new DelegationPolicyEngine(flags(false)).evaluate(7L, grant, action);
        PolicyDecision enabled = new DelegationPolicyEngine(flags(true)).evaluate(7L, grant, action);

        assertThat(disabled.decision()).isEqualTo("REQUIRE_CONFIRMATION");
        assertThat(disabled.reasonCode()).isEqualTo("R1_FEATURE_DISABLED");
        assertThat(enabled.decision()).isEqualTo("ALLOW");
        assertThat(enabled.autoExecuteWrite()).isTrue();
        assertThat(enabled.maxCallsPerRun()).isEqualTo(3);
    }

    @Test
    void r1RejectsFieldsOutsideTheStructuredGrant() {
        DelegationPolicyEngine engine = new DelegationPolicyEngine(flags(true));
        PolicyDecision decision = engine.evaluate(
                7L,
                grant(List.of(todoRule())),
                engine.normalize(
                        "todo.propose",
                        "WRITE_LOW",
                        true,
                        Map.of("projectId", 9, "title", "跟进异常", "assignee", "outside-grant")
                )
        );

        assertThat(decision.decision()).isEqualTo("DENY");
        assertThat(decision.reasonCode()).isEqualTo("FIELD_NOT_AUTHORIZED");
    }

    @Test
    void validationRejectsWildcardOrNonCompensatedR1Rules() {
        DelegationPolicyEngine engine = new DelegationPolicyEngine(flags(true));
        Map<String, Object> wildcard = Map.of(
                "id", "r1-wildcard",
                "effect", "allow",
                "toolRefs", List.of("*"),
                "operations", List.of("create"),
                "resourceScope", Map.of("projectIds", List.of(9)),
                "allowedFields", List.of("title"),
                "maxRisk", "R1",
                "requiresReversible", true,
                "maxCallsPerRun", 1
        );
        Map<String, Object> noCompensation = Map.of(
                "id", "r1-no-compensation",
                "effect", "allow",
                "toolRefs", List.of("todo.propose"),
                "operations", List.of("create"),
                "resourceScope", Map.of("projectIds", List.of(9)),
                "allowedFields", List.of("title"),
                "maxRisk", "R1",
                "requiresReversible", false,
                "maxCallsPerRun", 1
        );

        assertThatThrownBy(() -> engine.validateRules(List.of(wildcard)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("通配工具");
        assertThatThrownBy(() -> engine.validateRules(List.of(noCompensation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("补偿能力");
    }

    @Test
    void explicitDenyTakesPrecedenceRegardlessOfRuleOrder() {
        DelegationPolicyEngine engine = new DelegationPolicyEngine(flags(true));
        Map<String, Object> deny = Map.of(
                "id", "deny-project-read",
                "effect", "deny",
                "toolRefs", List.of("project.inspect"),
                "operations", List.of("read"),
                "resourceScope", Map.of("projectIds", List.of(9)),
                "allowedFields", List.of(),
                "maxRisk", "R0",
                "requiresReversible", false,
                "maxCallsPerRun", 10
        );

        PolicyDecision decision = engine.evaluate(
                7L,
                grant(List.of(readRule(), deny)),
                engine.normalize("project.inspect", "READ", false, Map.of("projectId", 9))
        );

        assertThat(decision.decision()).isEqualTo("DENY");
        assertThat(decision.reasonCode()).isEqualTo("EXPLICIT_DENY");
        assertThat(decision.matchedRuleId()).isEqualTo("deny-project-read");
    }

    @Test
    void confirmationDecisionContinuesIntoTheControlledConfirmationFlow() {
        RuntimeDecision decision = new RuntimeDecision(
                true,
                "REQUIRE_CONFIRMATION",
                "R1_FEATURE_DISABLED",
                "create-project-task",
                2,
                false,
                99L
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.requiresConfirmation()).isTrue();
        assertThat(decision.autoExecuteWrite()).isFalse();
    }

    @Test
    void delegatedPlannerOnlySeesExplicitlyAllowedToolsAndDenyWins() {
        DelegationPolicyEngine engine = new DelegationPolicyEngine(flags(true));
        Map<String, Object> denyProjectRead = Map.of(
                "id", "deny-project-read",
                "effect", "deny",
                "toolRefs", List.of("project.inspect"),
                "operations", List.of("read"),
                "resourceScope", Map.of("projectIds", List.of(9)),
                "allowedFields", List.of(),
                "maxRisk", "R0",
                "requiresReversible", false,
                "maxCallsPerRun", 10
        );

        List<String> tools = engine.plannedToolRefs(
                grant(List.of(readRule(), todoRule(), denyProjectRead))
        );

        assertThat(tools).containsExactly("todo.propose");
        assertThat(tools).doesNotContain("file.patch.propose", "project.inspect");
    }

    private static MissionFeatureFlags flags(boolean r1Enabled) {
        return new MissionFeatureFlags(
                true,
                true,
                r1Enabled,
                false,
                "7",
                "project-health,research-weekly,release-readiness",
                "todo.propose,file.patch.propose"
        );
    }

    private static GrantRecord grant(List<Object> rules) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 0, 0);
        return new GrantRecord(
                1L,
                10L,
                1,
                "ACTIVE",
                "test",
                rules,
                Map.of("maxToolCalls", 20),
                now.minusDays(1),
                now.plusDays(7),
                7L,
                now,
                "hash",
                now
        );
    }

    private static Map<String, Object> readRule() {
        return Map.of(
                "id", "read-project",
                "effect", "allow",
                "toolRefs", List.of("project.inspect"),
                "operations", List.of("read"),
                "resourceScope", Map.of("projectIds", List.of(9)),
                "allowedFields", List.of(),
                "maxRisk", "R0",
                "requiresReversible", false,
                "maxCallsPerRun", 10
        );
    }

    private static Map<String, Object> todoRule() {
        return Map.of(
                "id", "create-project-task",
                "effect", "allow",
                "toolRefs", List.of("todo.propose"),
                "operations", List.of("create"),
                "resourceScope", Map.of("projectIds", List.of(9)),
                "allowedFields", List.of("title", "dueAt", "items"),
                "maxRisk", "R1",
                "requiresReversible", true,
                "maxCallsPerRun", 3
        );
    }
}
