package com.example.aiapp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDebugMetadataSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatDebugMetadataSanitizer sanitizer = new ChatDebugMetadataSanitizer(objectMapper);

    @Test
    void rebuildsDebugMetadataWithoutRawContextsResultsReasoningOrCredentials() throws Exception {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("agent", "writer_agent");
        trace.put("secretPrompt", "must-not-survive");
        trace.put("chainOfThought", "private reasoning");
        trace.put("writerInput", Map.of(
                "memoryContextChars", 88,
                "toolContextChars", 21,
                "ragContextCount", 2,
                "combinedContextChars", 109,
                "memoryContext", "password=LEAKME1234567890",
                "toolContext", "Authorization: Bearer LEAKME1234567890",
                "combinedContext", "private context",
                "ragContexts", List.of(Map.of("chunk", "private chunk"))
        ));
        trace.put("memorySummary", Map.of(
                "summaryChars", 12,
                "summaryPreview", "用户在做 AI 应用",
                "shortTermCount", 1,
                "shortTermPreview", List.of(Map.of("role", "user", "content", "token=LEAKME1234567890")),
                "structuredCount", 1,
                "structuredPreview", List.of("occupation=程序员"),
                "retrievedCount", 1,
                "retrievedPreview", List.of("用户正在做量化模型")
        ));

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("id", 9);
        operation.put("toolName", "todo");
        operation.put("operationType", "todo.create");
        operation.put("riskLevel", "write");
        operation.put("summary", "创建验收待办");
        operation.put("status", "pending");
        operation.put("error", "ordinary private operation error");
        operation.put("payload", Map.of(
                "title", "发布验收",
                "dueAt", "2026-09-05T10:00:00Z",
                "privateBody", "must-not-survive"
        ));
        operation.put("result", Map.of("password", "LEAKME1234567890"));
        operation.put("sourceText", "private source");

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("tool", "todo");
        tool.put("ok", true);
        tool.put("status", "pending");
        tool.put("requiresConfirmation", true);
        tool.put("operation", operation);
        tool.put("content", "已准备操作");
        tool.put("storageError", "ordinary private storage error");
        tool.put("error", Map.of(
                "code", "TIMEOUT",
                "message", "ordinary private provider error",
                "retryable", true
        ));
        tool.put("result", Map.of("authorization", "Bearer LEAKME1234567890"));
        tool.put("rawParameters", Map.of("apiKey", "LEAKME1234567890"));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("debugVersion", "chat-debug-v1");
        raw.put("knowledgeBaseIds", List.of("200", "bad-id", "200"));
        raw.put("retrievalScope", Map.of(
                "requested", true,
                "executed", true,
                "knowledgeBaseCount", 1,
                "queryCount", 2,
                "contextCount", 1,
                "reason", "知识库检索完成",
                "rawQuery", "must-not-survive"
        ));
        raw.put("sources", List.of(Map.of(
                "docId", "file-1",
                "title", "knowledge.docx #1",
                "chunk", "LoRA 通过低秩矩阵减少可训练参数。",
                "score", "0.91",
                "rawVector", List.of(1, 2, 3)
        )));
        raw.put("agentTrace", List.of(trace));
        raw.put("toolResults", List.of(tool));
        raw.put("review", Map.of(
                "approved", true,
                "reason", "规则通过",
                "privateReasoning", "must-not-survive",
                "recalledMemoryRefs", List.of(Map.of("value", "LEAKME1234567890"))
        ));
        raw.put("usedMemories", List.of(Map.of(
                "memoryId", "profile:12",
                "title", "当前角色",
                "valueSummary", "程序员",
                "reasonCode", "QUESTION_RELEVANT",
                "reliability", "RELIABLE",
                "sourceCount", 1,
                "rawSource", "must-not-survive"
        )));
        raw.put("memoryExclusions", Map.of("conflicted", 1, "sensitive", 2, "governed", 3));

        Map<String, Object> safe = sanitizer.sanitize(raw);
        String json = objectMapper.writeValueAsString(safe);

        assertThat(safe.get("debugVersion")).isEqualTo("chat-debug-v1");
        assertThat(safe.get("knowledgeBaseIds")).isEqualTo(List.of("200"));
        assertThat(json)
                .contains("用户在做 AI 应用")
                .contains("创建验收待办")
                .doesNotContain("LEAKME1234567890")
                .doesNotContain("must-not-survive")
                .doesNotContain("secretPrompt")
                .doesNotContain("chainOfThought")
                .doesNotContain("memoryContext\"")
                .doesNotContain("toolContext\"")
                .doesNotContain("combinedContext\"")
                .doesNotContain("ragContexts")
                .doesNotContain("rawParameters")
                .doesNotContain("privateReasoning")
                .doesNotContain("recalledMemoryRefs")
                .doesNotContain("privateBody")
                .doesNotContain("sourceText")
                .doesNotContain("ordinary private operation error")
                .doesNotContain("ordinary private storage error")
                .doesNotContain("ordinary private provider error");
        Map<?, ?> safeOperation = (Map<?, ?>) ((Map<?, ?>) ((List<?>) safe.get("toolResults")).get(0)).get("operation");
        Map<?, ?> safeTool = (Map<?, ?>) ((List<?>) safe.get("toolResults")).get(0);
        assertThat(safeTool.get("status")).isEqualTo("pending");
        assertThat(safeTool.get("summary")).isEqualTo("创建验收待办");
        assertThat(safeTool.get("error")).isEqualTo(Map.of("code", "TIMEOUT", "retryable", true));
        assertThat(safeOperation.get("payload")).isEqualTo(Map.of(
                "title", "发布验收",
                "dueAt", "2026-09-05T10:00:00Z"
        ));
    }

    @Test
    void boundsItemsAndSerializedDebugEnvelope() throws Exception {
        String largeChineseChunk = "知".repeat(5_000);
        List<Map<String, Object>> sources = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        List<Map<String, Object>> memories = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            sources.add(Map.of(
                    "docId", "file-" + index,
                    "title", "文档 " + index,
                    "chunk", largeChineseChunk,
                    "score", "0.9"
            ));
        }
        for (int index = 0; index < 50; index++) {
            traces.add(Map.of(
                    "agent", "memory_agent",
                    "selectedTools", List.of(largeChineseChunk, largeChineseChunk, largeChineseChunk),
                    "memorySummary", Map.of(
                            "summaryPreview", largeChineseChunk,
                            "structuredPreview", List.of(largeChineseChunk, largeChineseChunk),
                            "retrievedPreview", List.of(largeChineseChunk, largeChineseChunk)
                    )
            ));
        }
        for (int index = 0; index < 30; index++) {
            tools.add(Map.of(
                    "tool", "todo",
                    "ok", true,
                    "summary", largeChineseChunk,
                    "operation", Map.of(
                            "id", index + 1,
                            "toolName", "todo",
                            "operationType", "todo.create",
                            "summary", largeChineseChunk,
                            "payload", Map.of("title", largeChineseChunk, "dueAt", "2026-09-05")
                    )
            ));
        }
        for (int index = 0; index < 40; index++) {
            memories.add(Map.of(
                    "memoryId", "memory:" + index,
                    "title", largeChineseChunk,
                    "valueSummary", largeChineseChunk,
                    "reasonCode", "QUESTION_RELEVANT",
                    "reliability", "RELIABLE",
                    "sourceCount", 1
            ));
        }

        Map<String, Object> safe = sanitizer.sanitize(Map.of(
                "sources", sources,
                "agentTrace", traces,
                "toolResults", tools,
                "usedMemories", memories
        ));
        byte[] json = objectMapper.writeValueAsString(safe).getBytes(StandardCharsets.UTF_8);

        assertThat(safe.get("debugTruncated")).isEqualTo(true);
        assertThat(((List<?>) safe.get("sources")).size()).isLessThanOrEqualTo(10);
        assertThat(json.length).isLessThanOrEqualTo(128 * 1024);
        assertThat(((Map<?, ?>) safe.get("debugCounts")).get("sources")).isEqualTo(60);
    }

    @Test
    void doesNotMarkLegacyMetadataAsCompleteDebugSnapshot() {
        Map<String, Object> safe = sanitizer.sanitize(Map.of(
                "usedMemories", List.of(Map.of(
                        "memoryId", "profile:1",
                        "title", "当前角色",
                        "valueSummary", "程序员"
                )),
                "usage", Map.of("promptTokens", 10)
        ));

        assertThat(safe).doesNotContainKey("debugVersion");
        assertThat(safe.get("usedMemories")).asList().hasSize(1);
    }

    @Test
    void preservesActionConstraintReasonWithoutPersistingRawRecallContext() throws Exception {
        Map<String, Object> safe = sanitizer.sanitize(Map.of(
                "usedMemories", List.of(Map.of(
                        "memoryId", "profile:9",
                        "title", "互动边界",
                        "valueSummary", "不要主动追问家庭",
                        "reasonCode", "ACTION_CONSTRAINT",
                        "reliability", "RELIABLE",
                        "sourceCount", 1
                )),
                "compiled_context", "RAW_RECALL_BODY_DO_NOT_STORE",
                "governance", Map.of("blockedTerms", List.of("PRIVATE_DISABLED_MEMORY"))
        ));

        Map<?, ?> memory = (Map<?, ?>) ((List<?>) safe.get("usedMemories")).get(0);
        assertThat(memory.get("reasonCode")).isEqualTo("ACTION_CONSTRAINT");
        assertThat(memory.get("sourceCount")).isEqualTo(1);
        assertThat(objectMapper.writeValueAsString(safe))
                .doesNotContain("RAW_RECALL_BODY_DO_NOT_STORE", "PRIVATE_DISABLED_MEMORY");
    }

    @Test
    void neverTreatsRawToolContentAsSafeDisplaySummary() throws Exception {
        Map<String, Object> safe = sanitizer.sanitize(Map.of(
                "debugVersion", "chat-debug-v1",
                "toolResults", List.of(Map.of(
                        "tool", "third_party_search",
                        "ok", true,
                        "content", "客户的普通隐私正文（不是凭据格式）",
                        "summary", Map.of("private", "嵌套的普通隐私摘要"),
                        "error", Map.of("code", "ordinary private code", "retryable", true)
                ))
        ));

        String json = objectMapper.writeValueAsString(safe);
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) safe.get("toolResults")).get(0);
        assertThat(json).doesNotContain("客户的普通隐私正文");
        assertThat(json).doesNotContain("嵌套的普通隐私摘要");
        assertThat(json).doesNotContain("ordinary private code");
        assertThat(tool.get("summary")).isEqualTo("工具执行已完成。");
        assertThat(tool.get("error")).isEqualTo(Map.of("retryable", true));
    }
}
