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

    @Test
    void retainsSafeCacheCountersAndResolutionWithoutInternalManifest() {
        var sanitizer = new ChatDebugMetadataSanitizer(new ObjectMapper());
        var plan = Map.of("actualQueryCount", 2, "candidateCache", Map.of("hit", 1, "miss", 1, "privateCacheKey", "private"),
                "queryResolution", Map.of("method", "rule", "effectiveQuestion", "2.4.1 具体改了什么", "history", "private"),
                "documentRevisions", Map.of("private", "internal"));
        var result = sanitizer.sanitize(Map.of("agentTrace", List.of(Map.of("agent", "rag_agent", "retrievalPlan", plan))));
        assertThat(result.toString()).contains("actualQueryCount=2", "hit=1", "miss=1", "2.4.1")
                .doesNotContain("private", "documentRevisions", "history");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatDebugMetadataSanitizer sanitizer = new ChatDebugMetadataSanitizer(objectMapper);

    @Test
    void rehydratesCanonicalLongStringsButRejectsMalformedOrUnsafeDisplayCounters() {
        var saved = sanitizer.sanitize(Map.of(
                "processStartedAt", "1789662595003", "processCompletedAt", "1789662596003",
                "reasoning", Map.of("text", "检查完成", "kind", "reasoning", "sequence", "12"),
                "agentProgress", List.of(Map.of("turnId", "turn-1", "step", "writer_agent",
                        "status", "completed", "sequence", "13"))));
        assertThat(saved).containsEntry("processStartedAt", 1789662595003L).containsEntry("processCompletedAt", 1789662596003L);
        assertThat(saved.get("reasoning")).isEqualTo(Map.of("text", "检查完成", "kind", "reasoning", "sequence", 12L));
        assertThat(saved.get("agentProgress").toString()).contains("sequence=13");
        for (Object sequence : List.of("0", "-1", "01", " 1", "1 ", "+1", "1.5", "1e3", "NaN",
                "9007199254740992", "99999999999999999999999", true, 1.5, -1)) {
            assertThat(sanitizer.progressEvent(Map.of("step", "writer_agent", "status", "running",
                    "sequence", sequence), "turn-1")).as("sequence %s", sequence).isEmpty();
            assertThat(sanitizer.sanitize(Map.of("reasoning", Map.of("text", "无效", "kind", "reasoning", "sequence", sequence))))
                    .doesNotContainKey("reasoning");
        }
    }

    @Test
    void displayReasoningUsesBoundedAllowlistAndPreservesWhitespaceWithoutPrivateFields() {
        var safe = sanitizer.sanitize(Map.of("reasoning", Map.of(
                "text", "先检查 \n 再比较\u0000", "kind", "summary", "sequence", 2,
                "rawPrompt", "PRIVATE"), "processStartedAt", 1000L, "processCompletedAt", -2));
        assertThat(safe.get("reasoning")).isEqualTo(Map.of("text", "先检查 \n 再比较", "kind", "summary", "sequence", 2L));
        assertThat(safe).containsEntry("processStartedAt", 1000L).doesNotContainKey("processCompletedAt");
        assertThat(safe.toString()).doesNotContain("PRIVATE", "rawPrompt");
        var masked = sanitizer.reasoningEvent(Map.of("turnId", "turn-1", "step", "writer_agent",
                "kind", "reasoning", "sequence", 1, "delta", "Authorization: Bearer secret-token-value"), "turn-1");
        assertThat(masked.get("delta")).isEqualTo("[已隐藏]");
        var invalid = sanitizer.sanitize(Map.of("reasoning", Map.of("text", "PRIVATE", "kind", "internal", "sequence", 1)));
        assertThat(invalid).doesNotContainKey("reasoning");
        var capped = sanitizer.reasoningEvent(Map.of("turnId", "turn-1", "step", "writer_agent",
                "kind", "summary", "sequence", 1, "delta", "文".repeat(15_999) + "😀"), "turn-1");
        assertThat((String) capped.get("delta")).hasSize(15_999).doesNotEndWith("\uD83D");
    }

    @Test
    void retainsAllFourQueryTimingsAndLaterTotalsButRejectsUnknownKeys() {
        Map<String, Object> timings = new LinkedHashMap<>();
        for (int query = 1; query <= 4; query++) {
            timings.put("query_" + query + "_ms", 100);
            for (String phase : List.of("recall", "embedding", "vector_search", "keyword_search", "rerank", "merge", "parent_expansion", "qdrant_prepare")) {
                timings.put("query_" + query + "_" + phase + "_ms", 10);
            }
        }
        timings.put("sufficiency_ms", 15);
        timings.put("diagnostics_ms", 2);
        timings.put("total_ms", 450);
        timings.put("private_database_name", 123);
        timings.put("query_5_ms", 123);
        timings.put("query_1_sufficiency_ms", 123);
        var step = sanitizer.progressEvent(Map.of("sequence", 1, "step", "rag_agent", "status", "completed", "timings", timings), "turn-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> safe = (Map<String, Object>) step.get("timings");
        assertThat(safe).hasSize(39).containsKeys("query_4_parent_expansion_ms", "sufficiency_ms", "total_ms")
                .doesNotContainKeys("private_database_name", "query_5_ms", "query_1_sufficiency_ms");
        var metadata = sanitizer.sanitize(Map.of("agentTrace", List.of(Map.of("agent", "rag_agent", "timings", timings))));
        assertThat(metadata.toString()).contains("query_4_parent_expansion_ms", "sufficiency_ms", "total_ms")
                .doesNotContain("private_database_name", "query_5_ms");
    }

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
