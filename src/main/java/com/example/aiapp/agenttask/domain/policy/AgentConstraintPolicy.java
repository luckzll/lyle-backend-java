package com.example.aiapp.agenttask.domain.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic guard for user-authored negative constraints. */
public final class AgentConstraintPolicy {

    private AgentConstraintPolicy() {}

    public static Optional<String> violation(
            String toolRef,
            Map<String, Object> input,
            Map<String, Object> constraints,
            Long taskProjectId
    ) {
        List<String> forbidden = forbiddenTexts(constraints);
        String compactTool = compact(toolRef);
        if (forbidden.stream().anyMatch(value -> compact(value).contains(compactTool))) {
            return Optional.of("禁止事项明确排除了工具 " + toolRef + "。");
        }
        if ("research.search".equals(toolRef) && forbidden.stream().anyMatch(AgentConstraintPolicy::forbidsNetwork)) {
            return Optional.of("禁止事项不允许访问外网。");
        }
        if ("todo.propose".equals(toolRef) && forbidden.stream().anyMatch(AgentConstraintPolicy::forbidsTaskCreation)) {
            return Optional.of("禁止事项不允许创建任务或待办。");
        }
        if ("file.patch.propose".equals(toolRef) && forbidden.stream().anyMatch(AgentConstraintPolicy::forbidsFileChanges)) {
            return Optional.of("禁止事项不允许修改文件。");
        }
        if ("memory.snapshot".equals(toolRef) && forbidden.stream().anyMatch(AgentConstraintPolicy::forbidsMemory)) {
            return Optional.of("禁止事项不允许读取记忆。");
        }
        if ("project.inspect".equals(toolRef)) {
            if (taskProjectId == null || taskProjectId <= 0) {
                return Optional.of("项目巡检必须绑定目标项目。");
            }
            Long inputProjectId = positiveLong(input.get("projectId"));
            if (inputProjectId == null || !inputProjectId.equals(taskProjectId)) {
                return Optional.of("项目巡检输入必须与任务绑定的 projectId 一致。");
            }
        }
        return Optional.empty();
    }

    private static List<String> forbiddenTexts(Map<String, Object> constraints) {
        Object value = constraints == null ? null : constraints.get("forbidden");
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> values) {
            values.stream().map(String::valueOf).map(String::trim).filter(text -> !text.isBlank()).forEach(result::add);
        } else if (value != null) {
            String.valueOf(value).lines().map(String::trim).filter(text -> !text.isBlank()).forEach(result::add);
        }
        return result;
    }

    private static boolean forbidsNetwork(String value) {
        return contains(value, Set.of("不访问外网", "禁止访问外网", "不联网", "禁止联网", "离线执行", "nointernet", "nonetwork", "offlineonly"));
    }

    private static boolean forbidsTaskCreation(String value) {
        return contains(value, Set.of("不创建任务", "禁止创建任务", "不创建待办", "禁止创建待办", "notaskcreation", "notodo", "donotcreatetask"));
    }

    private static boolean forbidsFileChanges(String value) {
        return contains(value, Set.of("不改文件", "不修改文件", "禁止修改文件", "不写文件", "只读文件", "nofilechanges", "donotmodifyfiles", "readonlyfiles"));
    }

    private static boolean forbidsMemory(String value) {
        return contains(value, Set.of("不读取记忆", "禁止读取记忆", "nomemory", "donotreadmemory"));
    }

    private static boolean contains(String value, Set<String> phrases) {
        String compact = compact(value);
        return phrases.stream().anyMatch(compact::contains);
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
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
}
