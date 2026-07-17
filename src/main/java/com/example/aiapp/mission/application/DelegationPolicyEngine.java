package com.example.aiapp.mission.application;

import com.example.aiapp.mission.config.MissionFeatureFlags;
import com.example.aiapp.mission.infrastructure.MissionRepository.GrantRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DelegationPolicyEngine {

    private static final Set<String> R0_TOOLS = Set.of(
            "project.inspect",
            "release.check",
            "workspace.git_status",
            "workspace.docs_check",
            "research.search",
            "memory.snapshot",
            "tools.list",
            "analysis.synthesize"
    );
    private static final Set<String> R1_TOOLS = Set.of("todo.propose", "file.patch.propose");
    private static final Set<String> RULE_KEYS = Set.of(
            "id", "effect", "toolRefs", "operations", "resourceScope",
            "allowedFields", "maxRisk", "requiresReversible", "maxCallsPerRun"
    );
    private static final Set<String> RESOURCE_KEYS = Set.of("projectIds", "workspaceIds");

    private final MissionFeatureFlags featureFlags;

    public DelegationPolicyEngine(MissionFeatureFlags featureFlags) {
        this.featureFlags = featureFlags;
    }

    public ActionIntent normalize(
            String toolRef,
            String requestedRisk,
            boolean writeIntent,
            Map<String, Object> input
    ) {
        String risk = writeIntent ? "R1" : "R0";
        if (R1_TOOLS.contains(toolRef)) risk = "R1";
        if (!R0_TOOLS.contains(toolRef) && !R1_TOOLS.contains(toolRef)) risk = "R2";
        String operation = switch (toolRef == null ? "" : toolRef) {
            case "todo.propose" -> "create";
            case "file.patch.propose" -> "patch";
            case "research.search" -> "search";
            case "analysis.synthesize" -> "analyze";
            default -> "read";
        };
        Map<String, Object> resource = new LinkedHashMap<>();
        Long projectId = positiveLong(input == null ? null : input.get("projectId"));
        Long workspaceId = positiveLong(input == null ? null : input.get("workspaceId"));
        if (projectId != null) resource.put("projectId", projectId);
        if (workspaceId != null) resource.put("workspaceId", workspaceId);
        if (input != null && input.get("relativePath") != null) {
            resource.put("relativePath", String.valueOf(input.get("relativePath")));
        }
        boolean reversible = R1_TOOLS.contains(toolRef);
        return new ActionIntent(toolRef == null ? "" : toolRef, operation, risk, reversible, resource, input == null ? Map.of() : input);
    }

    public PolicyDecision evaluate(long userId, GrantRecord grant, ActionIntent action) {
        if (action.toolRef().isBlank()) return PolicyDecision.deny("TOOL_MISSING");
        if ("R2".equals(action.riskLevel()) || "R3".equals(action.riskLevel())) {
            return PolicyDecision.deny("SYSTEM_RISK_FORBIDDEN");
        }
        List<Map<String, Object>> rules = maps(grant.rules());
        List<Map<String, Object>> matchedAllows = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            if (!matches(rule, action)) continue;
            String effect = text(rule.getOrDefault("effect", "deny")).toLowerCase();
            String id = text(rule.get("id"));
            int maxCalls = integer(rule.get("maxCallsPerRun"), 20);
            if (!"allow".equals(effect)) {
                return new PolicyDecision("DENY", "EXPLICIT_DENY", id, false, maxCalls);
            }
            matchedAllows.add(rule);
        }
        if (matchedAllows.isEmpty()) return PolicyDecision.deny("NO_MATCHING_RULE");

        if ("R1".equals(action.riskLevel())) {
            for (Map<String, Object> matchedAllow : matchedAllows) {
                if (!allowedFields(matchedAllow, action)) continue;
                String id = text(matchedAllow.get("id"));
                int maxCalls = integer(matchedAllow.get("maxCallsPerRun"), 20);
                if (!action.reversible()) {
                    return new PolicyDecision(
                            "REQUIRE_CONFIRMATION", "REVERSIBILITY_UNAVAILABLE", id, false, maxCalls);
                }
                if (!featureFlags.allowsR1(userId, action.toolRef())) {
                    return new PolicyDecision(
                            "REQUIRE_CONFIRMATION", "R1_FEATURE_DISABLED", id, false, maxCalls);
                }
                if (bool(matchedAllow.get("requiresReversible")) && !action.reversible()) {
                    return new PolicyDecision(
                            "REQUIRE_CONFIRMATION", "REVERSIBILITY_REQUIRED", id, false, maxCalls);
                }
                return new PolicyDecision("ALLOW", "MATCHED_R1_RULE", id, true, maxCalls);
            }
            Map<String, Object> first = matchedAllows.get(0);
            return new PolicyDecision(
                    "DENY",
                    "FIELD_NOT_AUTHORIZED",
                    text(first.get("id")),
                    false,
                    integer(first.get("maxCallsPerRun"), 20)
            );
        }
        Map<String, Object> matchedAllow = matchedAllows.get(0);
        String id = text(matchedAllow.get("id"));
        int maxCalls = integer(matchedAllow.get("maxCallsPerRun"), 20);
        return new PolicyDecision("ALLOW", "MATCHED_R0_RULE", id, false, maxCalls);
    }

    public List<String> plannedToolRefs(GrantRecord grant) {
        List<Map<String, Object>> rules = maps(grant.rules());
        Set<String> candidates = new LinkedHashSet<>();
        for (Map<String, Object> rule : rules) {
            String effect = text(rule.getOrDefault("effect", "deny")).toLowerCase();
            List<String> toolRefs = strings(rule.get("toolRefs"));
            if ("allow".equals(effect)) {
                Set<String> expanded = toolRefs.contains("*")
                        ? R0_TOOLS
                        : new LinkedHashSet<>(toolRefs);
                for (String toolRef : expanded) {
                    if (isPlannableByRule(rule, toolRef)) candidates.add(toolRef);
                }
            }
        }
        for (Map<String, Object> rule : rules) {
            if ("allow".equalsIgnoreCase(text(rule.getOrDefault("effect", "deny")))) continue;
            List<String> toolRefs = strings(rule.get("toolRefs"));
            candidates.removeIf(toolRef ->
                    (toolRefs.contains("*") || toolRefs.contains(toolRef))
                            && isPlannableByRule(rule, toolRef)
            );
        }
        return List.copyOf(candidates);
    }

    public void validateRules(List<Object> rawRules) {
        List<Map<String, Object>> rules = maps(rawRules);
        if (rules.isEmpty() || rules.size() > 50) {
            throw new IllegalArgumentException("授权规则数量必须为 1～50 条。");
        }
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> rule : rules) {
            Set<String> unknown = new HashSet<>(rule.keySet());
            unknown.removeAll(RULE_KEYS);
            if (!unknown.isEmpty()) throw new IllegalArgumentException("授权规则包含未知字段：" + unknown);
            String id = text(rule.get("id"));
            if (id.isBlank() || id.length() > 128 || !ids.add(id)) {
                throw new IllegalArgumentException("授权规则 id 不能为空、过长或重复。");
            }
            String effect = text(rule.get("effect")).toLowerCase();
            if (!List.of("allow", "deny").contains(effect)) {
                throw new IllegalArgumentException("授权规则 effect 只支持 allow 或 deny。");
            }
            List<String> toolRefs = strings(rule.get("toolRefs"));
            if (toolRefs.isEmpty() || toolRefs.size() > 20) {
                throw new IllegalArgumentException("每条授权规则必须声明 1～20 个工具。");
            }
            String maxRisk = text(rule.getOrDefault("maxRisk", "R0")).toUpperCase();
            if (!List.of("R0", "R1").contains(maxRisk)) {
                throw new IllegalArgumentException("V6 授权规则只允许 R0 或 R1。");
            }
            if ("R1".equals(maxRisk) && (toolRefs.contains("*") || toolRefs.stream().anyMatch(tool -> !R1_TOOLS.contains(tool)))) {
                throw new IllegalArgumentException("R1 规则不得使用通配工具，且只能选择首批可补偿白名单。");
            }
            if ("R1".equals(maxRisk) && !bool(rule.get("requiresReversible"))) {
                throw new IllegalArgumentException("R1 规则必须要求补偿能力。");
            }
            List<String> operations = strings(rule.get("operations"));
            if (operations.isEmpty() || operations.size() > 20 || operations.contains("*")) {
                throw new IllegalArgumentException("授权规则必须声明 1～20 个具体操作。");
            }
            List<String> allowedFields = strings(rule.get("allowedFields"));
            if (allowedFields.size() > 50 || allowedFields.stream().anyMatch("*"::equals)) {
                throw new IllegalArgumentException("授权字段必须为具体字段且最多 50 个。");
            }
            Map<String, Object> scope = map(rule.get("resourceScope"));
            Set<String> unknownScope = new HashSet<>(scope.keySet());
            unknownScope.removeAll(RESOURCE_KEYS);
            if (!unknownScope.isEmpty()) throw new IllegalArgumentException("资源范围包含未知字段：" + unknownScope);
            if ("R1".equals(maxRisk)) {
                if (scope.isEmpty() || scope.values().stream().anyMatch(value -> "*".equals(value))) {
                    throw new IllegalArgumentException("R1 规则必须声明具体项目或工作区范围。");
                }
            }
            int maxCalls = integer(rule.get("maxCallsPerRun"), 20);
            if (maxCalls < 1 || maxCalls > 100) {
                throw new IllegalArgumentException("maxCallsPerRun 必须为 1～100。");
            }
        }
    }

    private static boolean matches(Map<String, Object> rule, ActionIntent action) {
        List<String> toolRefs = strings(rule.get("toolRefs"));
        if (!toolRefs.contains("*") && !toolRefs.contains(action.toolRef())) return false;
        List<String> operations = strings(rule.get("operations"));
        if (!operations.isEmpty() && !operations.contains(action.operation())) return false;
        String maxRisk = text(rule.getOrDefault("maxRisk", "R0")).toUpperCase();
        if ("R1".equals(action.riskLevel()) && !"R1".equals(maxRisk)) return false;
        Map<String, Object> scope = map(rule.get("resourceScope"));
        Long projectId = positiveLong(action.resource().get("projectId"));
        Long workspaceId = positiveLong(action.resource().get("workspaceId"));
        List<Long> projectIds = longs(scope.get("projectIds"));
        List<Long> workspaceIds = longs(scope.get("workspaceIds"));
        if (!projectIds.isEmpty() && (projectId == null || !projectIds.contains(projectId))) return false;
        if (!workspaceIds.isEmpty() && (workspaceId == null || !workspaceIds.contains(workspaceId))) return false;
        if ("R1".equals(action.riskLevel()) && projectIds.isEmpty() && workspaceIds.isEmpty()) return false;
        return true;
    }

    private static boolean isPlannableByRule(Map<String, Object> rule, String toolRef) {
        if (!R0_TOOLS.contains(toolRef) && !R1_TOOLS.contains(toolRef)) return false;
        if (R1_TOOLS.contains(toolRef)
                && !"R1".equalsIgnoreCase(text(rule.getOrDefault("maxRisk", "R0")))) {
            return false;
        }
        List<String> operations = strings(rule.get("operations"));
        return operations.contains(canonicalOperation(toolRef));
    }

    private static String canonicalOperation(String toolRef) {
        return switch (toolRef) {
            case "todo.propose" -> "create";
            case "file.patch.propose" -> "patch";
            case "research.search" -> "search";
            case "analysis.synthesize" -> "analyze";
            default -> "read";
        };
    }

    private static boolean allowedFields(Map<String, Object> rule, ActionIntent action) {
        Set<String> allowed = new HashSet<>(strings(rule.get("allowedFields")));
        allowed.add("projectId");
        allowed.add("workspaceId");
        return allowed.containsAll(action.input().keySet());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(List<Object> values) {
        if (values == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source
                ? new LinkedHashMap<>((Map<String, Object>) source)
                : new LinkedHashMap<>();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        return source.stream().map(String::valueOf).map(String::trim).filter(text -> !text.isBlank()).toList();
    }

    private static List<Long> longs(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        List<Long> result = new ArrayList<>();
        for (Object item : source) {
            Long parsed = positiveLong(item);
            if (parsed != null) result.add(parsed);
        }
        return result;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean result && result;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Long positiveLong(Object value) {
        if (value == null) return null;
        try {
            long parsed = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record ActionIntent(
            String toolRef,
            String operation,
            String riskLevel,
            boolean reversible,
            Map<String, Object> resource,
            Map<String, Object> input
    ) { }

    public record PolicyDecision(
            String decision,
            String reasonCode,
            String matchedRuleId,
            boolean autoExecuteWrite,
            int maxCallsPerRun
    ) {
        public static PolicyDecision deny(String reasonCode) {
            return new PolicyDecision("DENY", reasonCode, null, false, 0);
        }
    }
}
