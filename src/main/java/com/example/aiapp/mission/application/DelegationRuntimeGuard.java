package com.example.aiapp.mission.application;

import com.example.aiapp.agenttask.infrastructure.repository.AgentTaskRepository.TaskRecord;
import com.example.aiapp.mission.application.DelegationPolicyEngine.ActionIntent;
import com.example.aiapp.mission.application.DelegationPolicyEngine.PolicyDecision;
import com.example.aiapp.mission.config.MissionFeatureFlags;
import com.example.aiapp.mission.infrastructure.MissionRepository;
import com.example.aiapp.mission.infrastructure.MissionRepository.BudgetUsageRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.GrantRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.MissionRecord;
import com.example.aiapp.mission.infrastructure.MissionRepository.TaskDelegationRecord;
import com.example.aiapp.mission.infrastructure.events.MissionEventPublisher;
import com.example.aiapp.mission.infrastructure.metrics.MissionMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DelegationRuntimeGuard {

    private final MissionRepository repository;
    private final DelegationPolicyEngine policyEngine;
    private final MissionFeatureFlags featureFlags;
    private final ObjectMapper objectMapper;
    private final MissionEventPublisher events;
    private final MissionMetrics metrics;

    @Autowired
    public DelegationRuntimeGuard(
            MissionRepository repository,
            DelegationPolicyEngine policyEngine,
            MissionFeatureFlags featureFlags,
            ObjectMapper objectMapper,
            MissionEventPublisher events,
            MissionMetrics metrics
    ) {
        this.repository = repository;
        this.policyEngine = policyEngine;
        this.featureFlags = featureFlags;
        this.objectMapper = objectMapper;
        this.events = events;
        this.metrics = metrics;
    }

    public DelegationRuntimeGuard(
            MissionRepository repository,
            DelegationPolicyEngine policyEngine,
            MissionFeatureFlags featureFlags,
            ObjectMapper objectMapper
    ) {
        this(repository, policyEngine, featureFlags, objectMapper, null, null);
    }

    public RuntimeDecision authorize(
            TaskRecord task,
            long runId,
            Long stepRunId,
            String toolRef,
            String requestedRisk,
            boolean writeIntent,
            Map<String, Object> rawInput
    ) {
        TaskDelegationRecord context = repository.findTaskDelegation(task.id()).orElse(null);
        if (context == null || !context.delegated()) return RuntimeDecision.interactive();

        MissionRecord mission = repository.findMission(context.missionId()).orElse(null);
        Map<String, Object> input = new LinkedHashMap<>(rawInput == null ? Map.of() : rawInput);
        if (task.projectId() != null) input.putIfAbsent("projectId", task.projectId());
        ActionIntent action = policyEngine.normalize(toolRef, requestedRisk, writeIntent, input);

        if (mission == null || !"ACTIVE".equals(mission.status())) {
            return persist(context, stepRunId, action, "DENY", "MISSION_NOT_ACTIVE", null, false);
        }
        LocalDateTime now = now();
        if (mission.validFrom().isAfter(now) || !mission.expiresAt().isAfter(now)) {
            return persist(context, stepRunId, action, "DENY", "MISSION_OUTSIDE_VALIDITY", null, false);
        }
        if (!featureFlags.allowsExecution(task.userId(), mission.templateId())) {
            return persist(context, stepRunId, action, "DENY", "MISSION_EXECUTION_DISABLED", null, false);
        }
        if (context.snapshotGrantVersion() == null || context.activeGrantVersion() == null) {
            return persist(context, stepRunId, action, "DENY", "GRANT_MISSING", null, false);
        }

        GrantRecord snapshot = repository.findGrant(context.missionId(), context.snapshotGrantVersion()).orElse(null);
        GrantRecord current = repository.findGrant(context.missionId(), context.activeGrantVersion()).orElse(null);
        if (snapshot == null || current == null || !"ACTIVE".equals(current.status())) {
            return persist(context, stepRunId, action, "DENY", "GRANT_NOT_ACTIVE", null, false);
        }
        if (!snapshot.validFrom().isBefore(now.plusNanos(1)) || !snapshot.expiresAt().isAfter(now)
                || !current.validFrom().isBefore(now.plusNanos(1)) || !current.expiresAt().isAfter(now)) {
            return persist(context, stepRunId, action, "DENY", "GRANT_OUTSIDE_VALIDITY", null, false);
        }

        RuntimeDecision budgetDecision = checkPeriodBudget(context, mission, action, stepRunId);
        if (budgetDecision != null) return budgetDecision;

        PolicyDecision snapshotDecision = policyEngine.evaluate(task.userId(), snapshot, action);
        if (!"ALLOW".equals(snapshotDecision.decision())) {
            return persist(
                    context,
                    stepRunId,
                    action,
                    snapshotDecision.decision(),
                    "SNAPSHOT_" + snapshotDecision.reasonCode(),
                    snapshotDecision.matchedRuleId(),
                    false
            );
        }
        PolicyDecision currentDecision = policyEngine.evaluate(task.userId(), current, action);
        if (!"ALLOW".equals(currentDecision.decision())) {
            return persist(
                    context,
                    stepRunId,
                    action,
                    currentDecision.decision(),
                    "CURRENT_" + currentDecision.reasonCode(),
                    currentDecision.matchedRuleId(),
                    false
            );
        }
        int maxCalls = Math.min(snapshotDecision.maxCallsPerRun(), currentDecision.maxCallsPerRun());
        long attemptedCalls = repository.countTaskToolAttempts(task.id(), action.toolRef());
        boolean callLimitExceeded = stepRunId == null
                ? attemptedCalls >= maxCalls
                : attemptedCalls > maxCalls;
        if (maxCalls <= 0 || callLimitExceeded) {
            return persist(
                    context,
                    stepRunId,
                    action,
                    "DENY",
                    "RULE_CALL_LIMIT_EXCEEDED",
                    currentDecision.matchedRuleId(),
                    false
            );
        }
        return persist(
                context,
                stepRunId,
                action,
                "ALLOW",
                currentDecision.reasonCode(),
                currentDecision.matchedRuleId(),
                currentDecision.autoExecuteWrite()
        );
    }

    public void captureCompensation(
            TaskRecord task,
            long stepRunId,
            String toolRef,
            Map<String, Object> result
    ) {
        TaskDelegationRecord context = repository.findTaskDelegation(task.id()).orElse(null);
        if (context == null || !context.delegated() || result == null) return;
        Map<String, Object> compensation = map(result.get("compensation"));
        String snapshotRef = text(compensation.get("snapshotRef"));
        String resourceRef = text(compensation.get("resourceRef"));
        String expiresAt = text(compensation.get("expiresAt"));
        if (snapshotRef.isBlank() || expiresAt.isBlank()) return;
        LocalDateTime expiry;
        try {
            expiry = OffsetDateTime.parse(expiresAt).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException exc) {
            return;
        }
        String operation = textOr(compensation.get("operation"), operation(toolRef));
        long compensationId = repository.createCompensation(
                task.userId(),
                context.missionId(),
                task.id(),
                stepRunId,
                toolRef,
                operation,
                sha256(resourceRef),
                map(compensation.get("precondition")),
                snapshotRef,
                expiry,
                sha256(task.id() + ":" + stepRunId + ":" + snapshotRef)
        );
        if (metrics != null) metrics.compensation("AVAILABLE", toolRef);
        publish(context.missionId(), "compensation.updated", Map.of(
                "status", "AVAILABLE",
                "compensationId", String.valueOf(compensationId),
                "stepRunId", String.valueOf(stepRunId),
                "toolRef", toolRef,
                "summary", "可撤销动作已保存补偿快照。"
        ));
    }

    public void captureSecurityBoundary(
            TaskRecord task,
            long stepRunId,
            String sourceToolRef,
            List<Object> securityFlags
    ) {
        if (securityFlags == null || securityFlags.isEmpty()) return;
        TaskDelegationRecord context = repository.findTaskDelegation(task.id()).orElse(null);
        if (context == null || !context.delegated()) return;
        int grantVersion = context.activeGrantVersion() == null
                ? context.snapshotGrantVersion() == null ? 0 : context.snapshotGrantVersion()
                : context.activeGrantVersion();
        String reasonCode = "PROMPT_INJECTION_RESOURCE_EXPANSION";
        long decisionId = repository.createAuthorizationDecision(
                context.userId(),
                context.missionId(),
                context.taskId(),
                stepRunId,
                grantVersion,
                "resource.expand",
                "expand",
                sha256(sourceToolRef),
                "R3",
                "NOT_SUPPORTED",
                "DENY",
                reasonCode,
                null
        );
        if (metrics != null) {
            metrics.authorization("DENY", "R3", "resource.expand");
            metrics.denied(reasonCode);
        }
        publish(context.missionId(), "authorization.decided", Map.of(
                "status", "DENY",
                "authorizationDecisionId", String.valueOf(decisionId),
                "agentTaskId", String.valueOf(context.taskId()),
                "toolRef", "resource.expand",
                "riskLevel", "R3",
                "reasonCode", reasonCode,
                "summary", "外部内容试图扩大资源权限，已按默认拒绝策略拦截。"
        ));
        long exceptionId = repository.openException(
                context.userId(),
                context.missionId(),
                context.taskId(),
                "SECURITY",
                "HIGH",
                sha256(context.missionId() + ":" + context.taskId() + ":" + reasonCode),
                "检测到 Prompt Injection 资源扩张尝试",
                "外部内容包含越权指令；Lyle 未改变已批准的工具、资源或权限范围。",
                List.of(
                        "authorization-decision://" + decisionId,
                        "agent-step-run://" + stepRunId
                ),
                List.of("VIEW_EVIDENCE", "DISMISS", "END_RUN")
        );
        exceptionOpened(
                context.missionId(),
                exceptionId,
                "SECURITY",
                "HIGH",
                "检测到 Prompt Injection 资源扩张尝试"
        );
    }

    private RuntimeDecision checkPeriodBudget(
            TaskDelegationRecord context,
            MissionRecord mission,
            ActionIntent action,
            Long stepRunId
    ) {
        Period period = period(mission.periodType(), now());
        BudgetUsageRecord usage = repository.aggregateMissionUsage(
                context.missionId(),
                period.start(),
                period.end()
        );
        Map<String, Object> limits = mission.periodBudget();
        boolean exhausted =
                reached(limits, "maxToolCalls", usage.toolCalls())
                || reached(limits, "maxModelTokens", usage.modelTokens())
                || reached(limits, "maxCostMicros", usage.costMicros())
                || reached(limits, "maxWallTimeMillis", usage.elapsedMillis());
        if (!exhausted) return null;
        long exceptionId = repository.openException(
                context.userId(),
                context.missionId(),
                context.taskId(),
                "BUDGET",
                "HIGH",
                sha256("budget:" + context.missionId() + ":" + period.start()),
                "持续任务周期预算已用尽",
                "本周期预算已达到上限，Lyle 已在下一动作前安全停止。",
                List.of("agent-task://" + context.taskId()),
                List.of("ADJUST_GRANT", "END_RUN", "VIEW_EVIDENCE")
        );
        exceptionOpened(
                context.missionId(), exceptionId, "BUDGET", "HIGH",
                "持续任务周期预算已用尽");
        return persist(context, stepRunId, action, "DENY", "PERIOD_BUDGET_EXHAUSTED", null, false);
    }

    private RuntimeDecision persist(
            TaskDelegationRecord context,
            Long stepRunId,
            ActionIntent action,
            String decision,
            String reasonCode,
            String matchedRuleId,
            boolean autoExecuteWrite
    ) {
        int grantVersion = context.activeGrantVersion() == null
                ? context.snapshotGrantVersion() == null ? 0 : context.snapshotGrantVersion()
                : context.activeGrantVersion();
        long decisionId = repository.createAuthorizationDecision(
                context.userId(),
                context.missionId(),
                context.taskId(),
                stepRunId,
                grantVersion,
                action.toolRef(),
                action.operation(),
                resourceHash(action.resource()),
                action.riskLevel(),
                action.reversible() ? "AVAILABLE" : "NOT_SUPPORTED",
                decision,
                reasonCode,
                matchedRuleId
        );
        if (metrics != null) {
            metrics.authorization(decision, action.riskLevel(), action.toolRef());
            if ("DENY".equals(decision)) metrics.denied(reasonCode);
        }
        publish(context.missionId(), "authorization.decided", Map.of(
                "status", decision,
                "authorizationDecisionId", String.valueOf(decisionId),
                "agentTaskId", String.valueOf(context.taskId()),
                "toolRef", action.toolRef(),
                "riskLevel", action.riskLevel(),
                "reasonCode", reasonCode,
                "summary", "持续委托动作已完成授权判定。"
        ));
        if ("DENY".equals(decision)) {
            long exceptionId = repository.openException(
                    context.userId(),
                    context.missionId(),
                    context.taskId(),
                    reasonCode.contains("BUDGET") ? "BUDGET" : "AUTHORIZATION",
                    "HIGH",
                    sha256(context.missionId() + ":" + action.toolRef() + ":" + reasonCode),
                    "持续任务动作已被安全拦截",
                    "动作 " + action.toolRef() + " 未满足当前委托边界：" + reasonCode + "。",
                    List.of("authorization-decision://" + decisionId),
                    List.of("VIEW_EVIDENCE", "SKIP", "ADJUST_GRANT", "END_RUN")
            );
            exceptionOpened(
                    context.missionId(),
                    exceptionId,
                    reasonCode.contains("BUDGET") ? "BUDGET" : "AUTHORIZATION",
                    "HIGH",
                    "持续任务动作已被安全拦截"
            );
        }
        return new RuntimeDecision(
                true,
                decision,
                reasonCode,
                matchedRuleId,
                grantVersion,
                autoExecuteWrite,
                decisionId
        );
    }

    private void exceptionOpened(
            long missionId,
            long exceptionId,
            String type,
            String severity,
            String summary
    ) {
        if (metrics != null) metrics.exception(type, severity);
        publish(missionId, "exception.opened", Map.of(
                "status", "OPEN",
                "exceptionId", String.valueOf(exceptionId),
                "type", type,
                "severity", severity,
                "summary", summary
        ));
    }

    private void publish(long missionId, String eventType, Map<String, Object> payload) {
        if (events != null) events.publish(missionId, eventType, payload);
    }

    private String resourceHash(Map<String, Object> resource) {
        try {
            return sha256(objectMapper.writeValueAsString(resource == null ? Map.of() : resource));
        } catch (JsonProcessingException exc) {
            return sha256(String.valueOf(resource));
        }
    }

    private static boolean reached(Map<String, Object> limits, String key, long used) {
        long limit = longValue(limits.get(key));
        return limit > 0 && used >= limit;
    }

    private static Period period(String periodType, LocalDateTime value) {
        LocalDate date = value.toLocalDate();
        String normalized = text(periodType).toUpperCase();
        LocalDate startDate = switch (normalized) {
            case "DAILY" -> date;
            case "MONTHLY" -> date.withDayOfMonth(1);
            default -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        };
        LocalDate endDate = switch (normalized) {
            case "DAILY" -> startDate.plusDays(1);
            case "MONTHLY" -> startDate.plusMonths(1);
            default -> startDate.plusWeeks(1);
        };
        return new Period(startDate.atStartOfDay(), endDate.atStartOfDay());
    }

    private static String operation(String toolRef) {
        return switch (toolRef) {
            case "todo.propose" -> "create";
            case "file.patch.propose" -> "patch";
            default -> "read";
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException(exc);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source
                ? new LinkedHashMap<>((Map<String, Object>) source)
                : new LinkedHashMap<>();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try {
            return value == null ? 0 : Math.max(0, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String textOr(Object value, String fallback) {
        String result = text(value);
        return result.isBlank() ? fallback : result;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record Period(LocalDateTime start, LocalDateTime end) { }

    public record RuntimeDecision(
            boolean delegated,
            String decision,
            String reasonCode,
            String matchedRuleId,
            int grantVersion,
            boolean autoExecuteWrite,
            long authorizationDecisionId
    ) {
        public static RuntimeDecision interactive() {
            return new RuntimeDecision(false, "NOT_APPLICABLE", "INTERACTIVE_TASK", null, 0, false, 0);
        }

        public boolean allowed() {
            return !delegated || !"DENY".equals(decision);
        }

        public boolean requiresConfirmation() {
            return delegated && "REQUIRE_CONFIRMATION".equals(decision);
        }
    }
}
