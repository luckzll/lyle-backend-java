package com.example.aiapp.agenttask.domain.policy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentPolicyEngine {

    public static final Set<String> READ_TOOLS = Set.of(
            "project.inspect",
            "release.check",
            "workspace.git_status",
            "workspace.docs_check",
            "research.search",
            "memory.snapshot",
            "tools.list",
            "analysis.synthesize"
    );
    public static final Set<String> WRITE_TOOLS = Set.of("todo.propose", "file.patch.propose");

    public PolicyDecision evaluate(
            String toolRef,
            String riskLevel,
            boolean writeIntent,
            Map<String, Object> permissionSnapshot,
            Map<String, Object> input
    ) {
        if (toolRef == null || toolRef.isBlank()) {
            return new PolicyDecision(false, false, "步骤没有声明工具。", "TOOL_MISSING");
        }
        Set<String> catalog = writeIntent ? WRITE_TOOLS : READ_TOOLS;
        if (!catalog.contains(toolRef)) {
            return new PolicyDecision(false, false, "工具不在 V5 白名单中。", "TOOL_NOT_ALLOWED");
        }
        if (writeIntent && !"WRITE_LOW".equalsIgnoreCase(riskLevel)) {
            return new PolicyDecision(false, false, "首版只允许提出低风险写操作。", "RISK_NOT_ALLOWED");
        }
        if (!writeIntent && !List.of("READ", "EXTERNAL_READ").contains(riskLevel.toUpperCase())) {
            return new PolicyDecision(false, false, "只读步骤的风险声明无效。", "RISK_MISMATCH");
        }
        Object allowedTools = permissionSnapshot.get("allowedTools");
        if (allowedTools instanceof List<?> tools && tools.stream().noneMatch(toolRef::equals)) {
            return new PolicyDecision(false, false, "工具不在计划审批快照中。", "TOOL_OUTSIDE_SNAPSHOT");
        }
        if (input.containsKey("secret") || input.containsKey("secretValue")) {
            return new PolicyDecision(false, false, "Secret 只能通过引用注入。", "SECRET_IN_PAYLOAD");
        }
        return new PolicyDecision(true, writeIntent, writeIntent ? "需要单次确认。" : "只读调用已授权。", null);
    }

    public record PolicyDecision(boolean allowed, boolean requiresConfirmation, String reason, String errorCode) {
    }
}
