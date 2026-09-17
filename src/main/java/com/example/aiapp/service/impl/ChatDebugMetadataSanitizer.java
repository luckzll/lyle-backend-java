package com.example.aiapp.service.impl;

import com.example.aiapp.domain.dto.UsageDTO;
import com.example.aiapp.domain.vo.SourceVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Final storage boundary for chat diagnostics.
 *
 * <p>The Python response and SSE metadata are extensible objects. Debug data is
 * therefore rebuilt from a small display-only schema before it is written to
 * {@code chat_message.metadata_json}. In particular, prompt/context bodies,
 * raw tool parameters/results and internal model traces are never copied.
 * Provider-exposed reasoning has a separate, bounded display schema.</p>
 */
final class ChatDebugMetadataSanitizer {

    static final String DEBUG_VERSION = "chat-debug-v1";
    static final int MAX_REASONING_CHARS = 16_000;

    private static final int MAX_DEBUG_BYTES = 128 * 1024;
    private static final int MAX_TRACE_ITEMS = 40;
    private static final int MAX_SOURCE_ITEMS = 10;
    private static final int MAX_TOOL_ITEMS = 20;
    private static final int MAX_MEMORY_ITEMS = 20;
    private static final int MAX_KNOWLEDGE_BASE_IDS = 20;
    private static final Set<String> PROGRESS_STEPS = Set.of(
            "supervisor_agent", "memory_agent", "rag_agent", "tool_agent", "gather_agent",
            "project_resolver", "writer_agent", "reviewer_agent"
    );
    private static final Set<String> PROGRESS_STATUSES = Set.of("running", "completed", "failed", "skipped");
    private static final Set<String> TIMING_KEYS = Set.of(
            "query_rewrite_ms", "recall_ms", "embedding_ms", "vector_search_ms", "keyword_search_ms",
            "rerank_ms", "sufficiency_ms", "merge_ms", "parent_expansion_ms", "qdrant_prepare_ms",
            "diagnostics_ms", "total_ms", "writer_first_token_ms", "first_delta_ms", "writer_ms",
            "review_ms", "prepare_context_ms"
    );
    private static final Pattern QUERY_TIMING_KEY = Pattern.compile(
            "query_[1-4](?:_(?:recall|embedding|vector_search|keyword_search|rerank|merge|parent_expansion|qdrant_prepare))?_ms"
    );

    private static final Set<String> USAGE_SOURCES = Set.of(
            "PROVIDER_REPORTED", "ESTIMATED", "PARTIAL_ESTIMATE", "PENDING"
    );
    private static final Set<String> OPERATION_PAYLOAD_TYPES = Set.of("todo.create", "calendar.create");
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CREDENTIAL_STRUCTURE = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:authorization|proxy[-_]?authorization|password|passwd|token|access[-_]?token|"
                    + "refresh[-_]?token|api[-_]?key|client[-_]?secret|secret|cookie|set[-_]?cookie|credential|"
                    + "credentials|private[-_]?key|aws[-_]?secret[-_]?access[-_]?key|aws[-_]?access[-_]?key[-_]?id|"
                    + "aws[-_]?session[-_]?token|headers|env|config)(?:\\s*\\[[^]\\r\\n]{0,200}]\\s*|[\"']?\\s*)[:=]"
    );
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)\\b(?:basic|bearer|digest|aws4-hmac-sha256)\\s+[A-Za-z0-9._~+/=,:\"' -]{8,}"
    );
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)\\b([a-z][a-z0-9+.-]*://)([^/\\s:@]+):([^@/\\s]+)@"
    );
    private static final Pattern KNOWN_TOKEN = Pattern.compile(
            "(?i)\\b(?:sk-[a-z0-9_-]{12,}|ghp_[a-z0-9_]{20,}|AKIA[A-Z0-9]{16}|"
                    + "eyJ[a-z0-9_-]{10,}\\.[a-z0-9_-]{10,}\\.[a-z0-9_-]{8,})\\b"
    );

    private final ObjectMapper objectMapper;

    ChatDebugMetadataSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, Object> sanitize(Map<String, ?> raw) {
        Map<String, ?> source = raw == null ? Map.of() : raw;
        LimitState state = new LimitState();
        Map<String, Object> safe = new LinkedHashMap<>();
        // A version is a completeness marker, not merely a schema label.  Do
        // not upgrade legacy rows implicitly: the UI must still be able to
        // explain that pre-v1 history has no complete diagnostic snapshot.
        if (DEBUG_VERSION.equals(source.get("debugVersion"))) {
            safe.put("debugVersion", DEBUG_VERSION);
        }
        safe.put("knowledgeBaseIds", knowledgeBaseIds(source.get("knowledgeBaseIds"), state));
        safe.put("retrievalScope", retrievalScope(source.get("retrievalScope"), state));
        safe.put("sources", sources(source.get("sources"), state));
        safe.put("agentTrace", agentTrace(source.get("agentTrace"), state));
        if (source.containsKey("agentProgress")) {
            safe.put("agentProgress", agentProgress(source.get("agentProgress")));
        }
        Map<String, Object> reasoning = reasoning(source.get("reasoning"));
        if (!reasoning.isEmpty()) safe.put("reasoning", reasoning);
        for (String key : List.of("processStartedAt", "processCompletedAt")) {
            if (positiveSequence(source.get(key))) safe.put(key, ((Number) source.get(key)).longValue());
        }
        safe.put("toolResults", toolResults(source.get("toolResults"), state));
        safe.put("review", review(source.get("review"), state));
        safe.put("usage", usage(source.get("usage")));
        safe.put("usedMemories", usedMemories(source.get("usedMemories"), state));
        safe.put("memoryExclusions", memoryExclusions(source.get("memoryExclusions")));
        safe.put(
                "memoryExplanationVersion",
                nonBlank(text(source.get("memoryExplanationVersion"), 32, state), "v1")
        );
        state.truncated |= Boolean.TRUE.equals(source.get("debugTruncated"));
        safe.put("debugTruncated", state.truncated);
        if (state.redacted) {
            safe.put("debugRedacted", true);
        }
        safe.put("debugCounts", debugCounts(source));
        enforceTotalLimit(safe, state);
        safe.put("debugTruncated", state.truncated);
        return safe;
    }

    Map<String, Object> progressEvent(Map<String, ?> source, String turnId) {
        if (source == null || !(source.get("step") instanceof String step) || !PROGRESS_STEPS.contains(step)
                || !(source.get("status") instanceof String status) || !PROGRESS_STATUSES.contains(status)
                || !(source.get("sequence") instanceof Number sequence)
                || !Double.isFinite(sequence.doubleValue()) || sequence.longValue() < 1
                || sequence.doubleValue() != sequence.longValue() || sequence.longValue() > 9_007_199_254_740_991L) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("turnId", turnId);
        safe.put("sequence", sequence.longValue());
        safe.put("step", source.get("step"));
        safe.put("status", source.get("status"));
        putDuration(safe, "durationMs", source.get("durationMs"));
        putText(safe, "summary", source.get("summary"), 300, new LimitState());
        Map<String, Object> timings = timings(source.get("timings"));
        if (!timings.isEmpty()) safe.put("timings", timings);
        return safe;
    }

    Map<String, Object> reasoningEvent(Map<String, ?> source, String turnId) {
        if (source == null || !turnId.equals(source.get("turnId"))
                || !"writer_agent".equals(source.get("step"))
                || !reasoningKind(source.get("kind")) || !positiveSequence(source.get("sequence"))
                || !(source.get("delta") instanceof String delta) || delta.isEmpty()) return Map.of();
        String safeText = reasoningText(delta);
        if (safeText.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("turnId", turnId);
        safe.put("sequence", ((Number) source.get("sequence")).longValue());
        safe.put("step", "writer_agent");
        safe.put("kind", source.get("kind"));
        safe.put("delta", safeText);
        return safe;
    }

    private Map<String, Object> reasoning(Object value) {
        Map<?, ?> source = map(value);
        if (!reasoningKind(source.get("kind")) || !positiveSequence(source.get("sequence"))
                || !(source.get("text") instanceof String text)) return Map.of();
        String safeText = reasoningText(text);
        if (safeText.isEmpty()) return Map.of();
        return Map.of("text", safeText, "kind", source.get("kind"),
                "sequence", ((Number) source.get("sequence")).longValue());
    }

    private boolean reasoningKind(Object value) {
        return "reasoning".equals(value) || "summary".equals(value);
    }

    private boolean positiveSequence(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.longValue() > 0 && number.doubleValue() == number.longValue()
                && number.longValue() <= 9_007_199_254_740_991L;
    }

    private String reasoningText(String value) {
        // Preserve token boundaries and newlines; ordinary diagnostic text() trims them.
        String safe = maskSecrets(value.replaceAll("[\\p{Cc}&&[^\\n\\r\\t]]", ""), new LimitState());
        return truncateReasoning(safe, MAX_REASONING_CHARS);
    }

    static String truncateReasoning(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        int end = Math.max(0, maxChars);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private List<Map<String, Object>> agentProgress(Object value) {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Object item : list(value).stream().limit(64).toList()) {
            Map<?, ?> raw = map(item);
            Map<String, Object> source = new LinkedHashMap<>();
            raw.forEach((key, data) -> source.put(String.valueOf(key), data));
            Map<String, Object> event = progressEvent(source, text(source.get("turnId"), 160, new LimitState()));
            if (event.isEmpty()) continue;
            String step = String.valueOf(event.get("step"));
            Map<String, Object> previous = latest.get(step);
            if (previous == null || ((Number) event.get("sequence")).longValue()
                    > ((Number) previous.get("sequence")).longValue()) latest.put(step, event);
        }
        return new ArrayList<>(latest.values());
    }

    private Map<String, Object> timings(Object value) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map(value).entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (TIMING_KEYS.contains(key) || QUERY_TIMING_KEY.matcher(key).matches()) {
                putDuration(safe, key, entry.getValue());
            }
            if (safe.size() >= 64) break;
        }
        return safe;
    }

    private void putDuration(Map<String, Object> target, String key, Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0 && number.doubleValue() <= 86_400_000) {
            target.put(key, number.doubleValue());
        }
    }

    private List<String> knowledgeBaseIds(Object value, LimitState state) {
        if (!(value instanceof List<?> values)) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (values.size() > MAX_KNOWLEDGE_BASE_IDS) state.truncated = true;
        for (Object item : values) {
            String candidate = item == null ? "" : String.valueOf(item).strip();
            if (!candidate.matches("[1-9][0-9]{0,18}")) continue;
            ids.add(candidate);
            if (ids.size() >= MAX_KNOWLEDGE_BASE_IDS) break;
        }
        return List.copyOf(ids);
    }

    private Map<String, Object> retrievalScope(Object value, LimitState state) {
        Map<?, ?> source = map(value);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        copyBoolean(source, safe, "requested");
        copyBoolean(source, safe, "executed");
        copyBoolean(source, safe, "skipped");
        copyCount(source, safe, "knowledgeBaseCount");
        copyCount(source, safe, "queryCount");
        copyCount(source, safe, "contextCount");
        putText(safe, "reason", source.get("reason"), 500, state);
        return safe;
    }

    private List<Map<String, Object>> sources(Object value, LimitState state) {
        List<?> values = list(value);
        if (values.size() > MAX_SOURCE_ITEMS) state.truncated = true;
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Object item : values) {
            Map<?, ?> source = sourceMap(item);
            if (source.isEmpty()) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("docId", text(source.get("docId"), 160, state));
            result.put("title", text(source.get("title"), 300, state));
            result.put("chunk", text(source.get("chunk"), 4_000, state));
            putText(result, "score", source.get("score"), 40, state);
            putText(result, "backend", source.get("backend"), 80, state);
            putText(result, "hitModes", source.get("hitModes"), 120, state);
            putText(result, "chunkIndex", source.get("chunkIndex"), 40, state);
            putText(result, "parentTitle", source.get("parentTitle"), 300, state);
            putText(result, "startChar", source.get("startChar"), 40, state);
            putText(result, "endChar", source.get("endChar"), 40, state);
            safe.add(result);
            if (safe.size() >= MAX_SOURCE_ITEMS) break;
        }
        return safe;
    }

    private Map<?, ?> sourceMap(Object value) {
        if (value instanceof SourceVO source) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("docId", source.docId());
            result.put("title", source.title());
            result.put("chunk", source.chunk());
            result.put("score", source.score());
            result.put("backend", source.backend());
            result.put("hitModes", source.hitModes());
            result.put("chunkIndex", source.chunkIndex());
            result.put("parentTitle", source.parentTitle());
            result.put("startChar", source.startChar());
            result.put("endChar", source.endChar());
            return result;
        }
        return map(value);
    }

    private List<Map<String, Object>> agentTrace(Object value, LimitState state) {
        List<?> values = list(value);
        if (values.size() > MAX_TRACE_ITEMS) state.truncated = true;
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Object item : values) {
            Map<?, ?> source = map(item);
            if (source.isEmpty()) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", nonBlank(text(source.get("agent"), 80, state), "unknown"));
            for (String key : List.of(
                    "approved", "skipped", "streaming", "ruleBlocked", "sufficient",
                    "deterministicMemoryOverview", "backgroundWriteDeferred"
            )) {
                copyBoolean(source, result, key);
            }
            for (String key : List.of(
                    "answerChars", "rewriteAttempt", "toolCount", "contextCount", "queryCount",
                    "elapsedMs", "candidateCount", "imageCount", "requestedCount", "completedCount",
                    "knowledgeBaseCount"
            )) {
                copyCount(source, result, key);
            }
            if (!result.containsKey("elapsedMs") && source.get("elapsedMillis") != null) {
                result.put("elapsedMs", count(source.get("elapsedMillis")));
            }
            copyDecimal(source, result, "score");
            putText(result, "intent", source.get("intent"), 40, state);
            putText(result, "action", source.get("action"), 80, state);
            putText(result, "tool", source.get("tool"), 100, state);
            putText(result, "batchId", source.get("batchId"), 160, state);
            putStringList(result, "selectedTools", source.get("selectedTools"), 20, 80, state);
            putStringList(result, "nextAgents", source.get("nextAgents"), 20, 80, state);
            putStringList(result, "receivedAgents", source.get("receivedAgents"), 20, 80, state);
            putStringList(result, "queries", source.get("queries"), 5, 500, state);

            Map<String, Object> decision = decision(source.get("decision"), state);
            if (!decision.isEmpty()) result.put("decision", decision);
            Map<String, Object> writerInput = writerInput(source.get("writerInput"), state);
            if (!writerInput.isEmpty()) result.put("writerInput", writerInput);
            Map<String, Object> memorySummary = memorySummary(source.get("memorySummary"), state);
            if (!memorySummary.isEmpty()) result.put("memorySummary", memorySummary);
            Map<String, Object> timings = timings(source.get("timings"));
            if (!timings.isEmpty()) result.put("timings", timings);
            Map<String, Object> plan = retrievalPlan(source.get("retrievalPlan"), state);
            if (!plan.isEmpty()) result.put("retrievalPlan", plan);
            safe.add(result);
            if (safe.size() >= MAX_TRACE_ITEMS) break;
        }
        return safe;
    }

    private Map<String, Object> decision(Object value, LimitState state) {
        Map<?, ?> source = map(value);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("should_recall", "should_retrieve", "should_call_tools", "needs_clarification")) {
            copyBoolean(source, safe, key);
        }
        copyDecimal(source, safe, "confidence");
        putText(safe, "depth", source.get("depth"), 40, state);
        putText(safe, "router", source.get("router"), 40, state);
        putText(safe, "reason", source.get("reason"), 500, state);
        putStringList(safe, "selected_tools", source.get("selected_tools"), 20, 80, state);
        putStringList(safe, "rule_selected_tools", source.get("rule_selected_tools"), 20, 80, state);
        return safe;
    }

    private Map<String, Object> retrievalPlan(Object value, LimitState state) {
        Map<?, ?> source = map(value);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("mode", "fusionMode")) putText(safe, key, source.get(key), 40, state);
        for (String key : List.of("actualQueryCount", "candidateCount", "rerankSelectedCount", "parallelWorkers")) {
            copyCount(source, safe, key);
        }
        copyBoolean(source, safe, "adaptiveUsed");
        Map<?, ?> cache = map(source.get("candidateCache"));
        Map<String, Object> safeCache = new LinkedHashMap<>();
        for (String key : List.of("hit", "miss", "shared", "bypass")) copyCount(cache, safeCache, key);
        if (!safeCache.isEmpty()) safe.put("candidateCache", safeCache);
        Map<?, ?> resolution = map(source.get("queryResolution"));
        Map<String, Object> safeResolution = new LinkedHashMap<>();
        for (String key : List.of("method", "reason")) putText(safeResolution, key, resolution.get(key), 160, state);
        for (String key : List.of("originalQuestion", "effectiveQuestion")) putText(safeResolution, key, resolution.get(key), 500, state);
        if (!safeResolution.isEmpty()) safe.put("queryResolution", safeResolution);
        return safe;
    }

    private Map<String, Object> writerInput(Object value, LimitState state) {
        Map<?, ?> source = map(value);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "memoryContextChars", "excludedMemoryContextChars", "toolContextChars",
                "ragContextCount", "combinedContextChars"
        )) {
            copyCount(source, safe, key);
        }
        putText(safe, "memoryExclusionReason", source.get("memoryExclusionReason"), 80, state);
        return safe;
    }

    private Map<String, Object> memorySummary(Object value, LimitState state) {
        Map<?, ?> source = map(value);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "summaryChars", "shortTermCount", "structuredCount", "semanticCount",
                "vectorCount", "retrievedCount", "profileCount"
        )) {
            copyCount(source, safe, key);
        }
        putText(safe, "summaryPreview", source.get("summaryPreview"), 1_000, state);
        putStringList(safe, "structuredPreview", source.get("structuredPreview"), 8, 500, state);
        putStringList(safe, "retrievedPreview", source.get("retrievedPreview"), 6, 500, state);
        List<Map<String, Object>> shortTerm = shortTermPreview(source.get("shortTermPreview"), state);
        if (!shortTerm.isEmpty()) safe.put("shortTermPreview", shortTerm);
        return safe;
    }

    private List<Map<String, Object>> shortTermPreview(Object value, LimitState state) {
        List<?> values = list(value);
        if (values.size() > 6) state.truncated = true;
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Object item : values) {
            Map<?, ?> source = map(item);
            if (source.isEmpty()) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            String role = text(source.get("role"), 24, state).toLowerCase(Locale.ROOT);
            result.put("role", Set.of("user", "assistant", "system").contains(role) ? role : "unknown");
            result.put("content", text(source.get("content"), 500, state));
            safe.add(result);
            if (safe.size() >= 6) break;
        }
        return safe;
    }

    private List<Map<String, Object>> toolResults(Object value, LimitState state) {
        List<?> values = list(value);
        if (values.size() > MAX_TOOL_ITEMS) state.truncated = true;
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Object item : values) {
            Map<?, ?> source = map(item);
            if (source.isEmpty()) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            String tool = nonBlank(text(source.get("tool"), 100, state), "unknown");
            result.put("tool", tool);
            result.put("ok", Boolean.TRUE.equals(source.get("ok")));
            putText(result, "status", source.get("status"), 40, state);
            putText(result, "batchStatus", source.get("batchStatus"), 40, state);
            putText(result, "batchId", source.get("batchId"), 160, state);
            putText(result, "operationType", source.get("operationType"), 100, state);
            putText(result, "riskLevel", source.get("riskLevel"), 40, state);
            copyBoolean(source, result, "requiresConfirmation");
            copyBoolean(source, result, "retryable");
            copyCount(source, result, "elapsedMs");
            for (String key : List.of(
                    "count", "resultCount", "itemCount", "extractedChars", "imageCount", "requestedCount",
                    "completedCount", "failedCount"
            )) {
                copyCount(source, result, key);
            }
            putText(result, "attachmentId", source.get("attachmentId"), 160, state);
            putText(result, "model", source.get("model"), 100, state);
            putText(result, "storageRequested", source.get("storageRequested"), 80, state);
            putText(result, "storageUsed", source.get("storageUsed"), 80, state);

            Map<String, Object> operation = pendingOperation(source.get("operation"), state);
            if (!operation.isEmpty()) result.put("operation", operation);
            Object summary = operation.get("summary");
            if (summary == null) summary = source.get("summary");
            String displaySummary = text(summary, 500, state);
            if (displaySummary.isBlank()) {
                displaySummary = Boolean.TRUE.equals(source.get("ok"))
                        ? "工具执行已完成。" : "工具执行未完成。";
            }
            result.put("summary", displaySummary);
            result.put("content", displaySummary);

            Map<String, Object> error = toolError(source.get("error"), state);
            if (!error.isEmpty()) result.put("error", error);
            safe.add(result);
            if (safe.size() >= MAX_TOOL_ITEMS) break;
        }
        return safe;
    }

    private Map<String, Object> pendingOperation(Object value, LimitState state) {
        Map<?, ?> source = map(value);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        long id = nonNegativeLong(source.get("id"));
        if (id > 0) safe.put("id", id);
        putText(safe, "toolName", source.get("toolName"), 100, state);
        String operationType = text(source.get("operationType"), 100, state);
        if (!operationType.isBlank()) safe.put("operationType", operationType);
        putText(safe, "riskLevel", source.get("riskLevel"), 40, state);
        putText(safe, "summary", source.get("summary"), 500, state);
        putText(safe, "status", source.get("status"), 40, state);
        for (String key : List.of("createdAt", "updatedAt", "confirmedAt", "rejectedAt")) {
            putText(safe, key, source.get(key), 80, state);
        }
        if (OPERATION_PAYLOAD_TYPES.contains(operationType)) {
            Map<?, ?> payload = map(source.get("payload"));
            Map<String, Object> safePayload = new LinkedHashMap<>();
            putText(safePayload, "title", payload.get("title"), 500, state);
            if ("todo.create".equals(operationType)) {
                putText(safePayload, "dueAt", payload.get("dueAt"), 80, state);
            } else {
                putText(safePayload, "startsAt", payload.get("startsAt"), 80, state);
            }
            safe.put("payload", safePayload);
        }
        return safe;
    }

    private Map<String, Object> toolError(Object value, LimitState state) {
        Map<?, ?> source = map(value);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        String code = text(source.get("code"), 80, state);
        if (SAFE_ERROR_CODE.matcher(code).matches()) {
            safe.put("code", code);
        }
        copyBoolean(source, safe, "retryable");
        return safe;
    }

    private Map<String, Object> review(Object value, LimitState state) {
        Map<?, ?> source = map(value);
        if (source.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of("approved", "skipped", "ruleBlocked")) {
            copyBoolean(source, safe, key);
        }
        copyDecimal(source, safe, "score");
        putText(safe, "v3Rule", source.get("v3Rule"), 80, state);
        putText(safe, "reason", source.get("reason"), 500, state);
        putText(safe, "rewrite_instruction", source.get("rewrite_instruction"), 500, state);
        return safe;
    }

    private Map<String, Object> usage(Object value) {
        Map<?, ?> source;
        if (value instanceof UsageDTO usage) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("promptTokens", usage.promptTokens());
            mapped.put("completionTokens", usage.completionTokens());
            mapped.put("callCount", usage.callCount());
            mapped.put("estimatedCostMicros", usage.estimatedCostMicros());
            mapped.put("measurementSource", usage.measurementSource());
            source = mapped;
        } else {
            source = map(value);
        }
        Object rawMeasurementSource = source.get("measurementSource");
        String measurementSource = rawMeasurementSource == null ? "PENDING" : String.valueOf(rawMeasurementSource);
        if (!USAGE_SOURCES.contains(measurementSource)) measurementSource = "PENDING";
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("promptTokens", count(source.get("promptTokens")));
        safe.put("completionTokens", count(source.get("completionTokens")));
        safe.put("callCount", count(source.get("callCount")));
        safe.put("estimatedCostMicros", nonNegativeLong(source.get("estimatedCostMicros")));
        safe.put("measurementSource", measurementSource);
        return safe;
    }

    private List<Map<String, Object>> usedMemories(Object value, LimitState state) {
        List<?> values = list(value);
        if (values.size() > MAX_MEMORY_ITEMS) state.truncated = true;
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Object item : values) {
            Map<?, ?> source = map(item);
            if (source.isEmpty()) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("memoryId", text(source.get("memoryId"), 160, state));
            result.put("title", text(source.get("title"), 240, state));
            result.put("valueSummary", text(source.get("valueSummary"), 240, state));
            result.put("reasonCode", text(source.get("reasonCode"), 80, state));
            result.put("reliability", text(source.get("reliability"), 40, state));
            result.put("sourceCount", count(source.get("sourceCount")));
            safe.add(result);
            if (safe.size() >= MAX_MEMORY_ITEMS) break;
        }
        return safe;
    }

    private Map<String, Object> memoryExclusions(Object value) {
        Map<?, ?> source = map(value);
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("conflicted", count(source.get("conflicted")));
        safe.put("sensitive", count(source.get("sensitive")));
        safe.put("governed", count(source.get("governed")));
        return safe;
    }

    private Map<String, Object> debugCounts(Map<String, ?> source) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("agentTrace", list(source.get("agentTrace")).size());
        counts.put("sources", list(source.get("sources")).size());
        counts.put("toolResults", list(source.get("toolResults")).size());
        counts.put("usedMemories", list(source.get("usedMemories")).size());
        return counts;
    }

    @SuppressWarnings("unchecked")
    private void enforceTotalLimit(Map<String, Object> safe, LimitState state) {
        if (sizeBytes(safe) <= MAX_DEBUG_BYTES) return;
        state.truncated = true;
        safe.put("debugTruncated", true);

        for (Map<String, Object> source : (List<Map<String, Object>>) safe.get("sources")) {
            source.put("chunk", clip(String.valueOf(source.getOrDefault("chunk", "")), 1_000));
        }
        for (Map<String, Object> trace : (List<Map<String, Object>>) safe.get("agentTrace")) {
            Object rawSummary = trace.get("memorySummary");
            if (!(rawSummary instanceof Map<?, ?> summary)) continue;
            Map<String, Object> compact = new LinkedHashMap<>();
            for (String key : List.of(
                    "summaryChars", "shortTermCount", "structuredCount", "semanticCount",
                    "vectorCount", "retrievedCount", "profileCount"
            )) {
                if (summary.get(key) != null) compact.put(key, summary.get(key));
            }
            if (summary.get("summaryPreview") != null) {
                compact.put("summaryPreview", clip(String.valueOf(summary.get("summaryPreview")), 240));
            }
            trace.put("memorySummary", compact);
        }
        if (sizeBytes(safe) <= MAX_DEBUG_BYTES) return;

        List<Map<String, Object>> sources = (List<Map<String, Object>>) safe.get("sources");
        safe.put("sources", compactSources(sources));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) safe.get("toolResults");
        safe.put("toolResults", tools.stream().limit(10).map(this::compactTool).toList());
        List<Map<String, Object>> memories = (List<Map<String, Object>>) safe.get("usedMemories");
        safe.put("usedMemories", memories.stream().limit(10).toList());
        if (sizeBytes(safe) <= MAX_DEBUG_BYTES) return;

        List<Map<String, Object>> traces = (List<Map<String, Object>>) safe.get("agentTrace");
        safe.put("agentTrace", traces.stream().limit(20).map(this::compactTrace).toList());
        if (sizeBytes(safe) <= MAX_DEBUG_BYTES) return;

        // A final aggressive tier guarantees the serialized envelope itself,
        // rather than merely each field, remains bounded under adversarial
        // combinations of maximum-length safe values.
        safe.put("sources", ((List<Map<String, Object>>) safe.get("sources")).stream()
                .limit(3).map(this::minimalSource).toList());
        safe.put("agentTrace", ((List<Map<String, Object>>) safe.get("agentTrace")).stream()
                .limit(8).map(this::minimalTrace).toList());
        safe.put("toolResults", ((List<Map<String, Object>>) safe.get("toolResults")).stream()
                .limit(5).map(this::minimalTool).toList());
        safe.put("usedMemories", ((List<Map<String, Object>>) safe.get("usedMemories")).stream()
                .limit(5).map(this::minimalMemory).toList());
        if (sizeBytes(safe) <= MAX_DEBUG_BYTES) return;

        // Keep aggregate evidence if serialization ever grows beyond the
        // conservative estimate because the schema is extended in the future.
        safe.put("sources", List.of());
        safe.put("agentTrace", List.of());
        safe.put("toolResults", List.of());
        safe.put("usedMemories", List.of());
        safe.put("review", Map.of());
    }

    private List<Map<String, Object>> compactSources(List<Map<String, Object>> sources) {
        return sources.stream().limit(5).map(item -> {
            Map<String, Object> compact = new LinkedHashMap<>(item);
            compact.put("chunk", clip(String.valueOf(item.getOrDefault("chunk", "")), 400));
            return compact;
        }).toList();
    }

    private Map<String, Object> compactTool(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "tool", "ok", "status", "batchStatus", "batchId", "operationType", "riskLevel",
                "requiresConfirmation", "retryable", "elapsedMs", "operation"
        )) {
            if (source.containsKey(key)) safe.put(key, source.get(key));
        }
        String summary = clip(String.valueOf(source.getOrDefault("summary", "")), 200);
        safe.put("summary", summary);
        safe.put("content", summary);
        return safe;
    }

    private Map<String, Object> compactTrace(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "agent", "approved", "skipped", "streaming", "ruleBlocked", "sufficient",
                "answerChars", "rewriteAttempt", "toolCount", "contextCount", "queryCount",
                "elapsedMs", "selectedTools", "writerInput", "memorySummary"
        )) {
            if (source.containsKey(key)) safe.put(key, source.get(key));
        }
        return safe;
    }

    private Map<String, Object> minimalSource(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        copyClipped(source, safe, "docId", 80);
        copyClipped(source, safe, "title", 120);
        copyClipped(source, safe, "chunk", 200);
        copyClipped(source, safe, "score", 32);
        copyClipped(source, safe, "backend", 48);
        return safe;
    }

    private Map<String, Object> minimalTrace(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "agent", "approved", "skipped", "streaming", "ruleBlocked", "sufficient",
                "answerChars", "toolCount", "contextCount", "queryCount", "elapsedMs", "writerInput"
        )) {
            if (source.containsKey(key)) safe.put(key, source.get(key));
        }
        Object rawSummary = source.get("memorySummary");
        if (rawSummary instanceof Map<?, ?> summary) {
            Map<String, Object> compact = new LinkedHashMap<>();
            for (String key : List.of(
                    "summaryChars", "shortTermCount", "structuredCount", "semanticCount",
                    "vectorCount", "retrievedCount", "profileCount"
            )) {
                if (summary.get(key) != null) compact.put(key, summary.get(key));
            }
            if (summary.get("summaryPreview") != null) {
                compact.put("summaryPreview", clip(String.valueOf(summary.get("summaryPreview")), 100));
            }
            safe.put("memorySummary", compact);
        }
        return safe;
    }

    private Map<String, Object> minimalTool(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : List.of(
                "ok", "requiresConfirmation", "retryable", "elapsedMs", "count", "resultCount"
        )) {
            if (source.containsKey(key)) safe.put(key, source.get(key));
        }
        copyClipped(source, safe, "tool", 80);
        copyClipped(source, safe, "status", 32);
        copyClipped(source, safe, "operationType", 80);
        copyClipped(source, safe, "riskLevel", 32);
        copyClipped(source, safe, "summary", 100);
        safe.put("content", safe.getOrDefault("summary", ""));
        if (source.get("operation") instanceof Map<?, ?> operation) {
            Map<String, Object> compactOperation = new LinkedHashMap<>();
            for (String key : List.of("id", "payload")) {
                if (operation.get(key) != null) compactOperation.put(key, operation.get(key));
            }
            copyClipped(operation, compactOperation, "toolName", 80);
            copyClipped(operation, compactOperation, "operationType", 80);
            copyClipped(operation, compactOperation, "status", 32);
            copyClipped(operation, compactOperation, "summary", 100);
            safe.put("operation", compactOperation);
        }
        return safe;
    }

    private Map<String, Object> minimalMemory(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        copyClipped(source, safe, "memoryId", 80);
        copyClipped(source, safe, "title", 80);
        copyClipped(source, safe, "valueSummary", 120);
        copyClipped(source, safe, "reasonCode", 64);
        copyClipped(source, safe, "reliability", 32);
        if (source.containsKey("sourceCount")) safe.put("sourceCount", source.get("sourceCount"));
        return safe;
    }

    private void copyClipped(Map<?, ?> source, Map<String, Object> target, String key, int maxChars) {
        if (source.get(key) != null) {
            target.put(key, clip(String.valueOf(source.get(key)), maxChars));
        }
    }

    private int sizeBytes(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private String text(Object value, int maxChars, LimitState state) {
        if (value == null) return "";
        if (!(value instanceof CharSequence || value instanceof Number || value instanceof Boolean)) {
            state.truncated = true;
            return "";
        }
        String raw = String.valueOf(value);
        String masked = maskSecrets(raw, state);
        if (masked.length() > maxChars) {
            state.truncated = true;
            return clip(masked, maxChars);
        }
        return masked;
    }

    private String maskSecrets(String value, LimitState state) {
        String deescaped = value;
        for (int index = 0; index < 4; index++) {
            String next = deescaped.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
            if (next.equals(deescaped)) break;
            deescaped = next;
        }
        if (PRIVATE_KEY.matcher(deescaped).find()
                || CREDENTIAL_STRUCTURE.matcher(deescaped).find()
                || AUTHORIZATION_VALUE.matcher(deescaped).find()) {
            state.redacted = true;
            return "[已隐藏]";
        }
        String masked = URL_USER_INFO.matcher(value).replaceAll("$1***:***@");
        masked = KNOWN_TOKEN.matcher(masked).replaceAll("***");
        if (!masked.equals(value)) state.redacted = true;
        return masked;
    }

    private static String clip(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars - 1)).stripTrailing() + "…";
    }

    private void putText(
            Map<String, Object> target,
            String key,
            Object value,
            int maxChars,
            LimitState state
    ) {
        String safe = text(value, maxChars, state);
        if (!safe.isBlank()) target.put(key, safe);
    }

    private void putStringList(
            Map<String, Object> target,
            String key,
            Object value,
            int maxItems,
            int maxChars,
            LimitState state
    ) {
        List<?> source = list(value);
        if (source.size() > maxItems) state.truncated = true;
        List<String> safe = source.stream()
                .limit(maxItems)
                .map(item -> text(item, maxChars, state))
                .filter(item -> !item.isBlank())
                .toList();
        if (!safe.isEmpty()) target.put(key, safe);
    }

    private void copyBoolean(Map<?, ?> source, Map<String, Object> target, String key) {
        if (source.get(key) instanceof Boolean value) target.put(key, value);
    }

    private void copyCount(Map<?, ?> source, Map<String, Object> target, String key) {
        if (source.get(key) != null) target.put(key, count(source.get(key)));
    }

    private void copyDecimal(Map<?, ?> source, Map<String, Object> target, String key) {
        Object raw = source.get(key);
        if (raw == null) return;
        try {
            double value = raw instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(raw));
            if (Double.isFinite(value)) target.put(key, value);
        } catch (NumberFormatException ignored) {
            // Invalid diagnostic numbers are omitted.
        }
    }

    private int count(Object value) {
        long result = nonNegativeLong(value);
        return (int) Math.min(result, 1_000_000_000L);
    }

    private long nonNegativeLong(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try {
            return Math.max(0, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> item ? item : Map.of();
    }

    private static final class LimitState {
        private boolean truncated;
        private boolean redacted;
    }
}
