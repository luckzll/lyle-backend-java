package com.example.aiapp.agenttask.domain.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Final storage boundary for Agent tool results.
 *
 * Python normally returns a redacted display summary, but Step, Artifact, event, and
 * review storage must remain safe even when the upstream response is compromised.
 * Sensitive results are therefore rebuilt from a strict schema instead of being
 * cleaned with a field blacklist or trusted because they carry {@code redacted=true}.
 */
public final class AgentToolOutputSanitizer {

    public enum ConsumedSensitivity {
        NONE, PERSONAL, EXTERNAL_CONTENT
    }

    private static final Set<String> SENSITIVE_TOOLS = Set.of("memory.snapshot", "research.search");
    private static final Set<String> RESULT_STATUSES = Set.of("SUCCESS", "PARTIAL", "ERROR");
    private static final Set<String> CRITERION_STATUSES = Set.of("COMPLETE", "INCOMPLETE", "UNVERIFIED");
    private static final Set<String> SECURITY_FLAGS = Set.of(
            "PROMPT_INJECTION_PATTERN", "DATA_EXFILTRATION_PATTERN"
    );
    private static final Set<String> RAW_KEYS = Set.of(
            "raw", "content", "fullcontent", "body", "snippet", "text",
            "reasoning", "privatereasoning", "chainofthought", "modelreasoning"
    );
    private static final Pattern MEMORY_SUMMARY = Pattern.compile(
            "^(?:已读取个人记忆索引|个人记忆索引读取失败)：长期摘要 \\d{1,10} 条，结构化记忆 \\d{1,10} 条，"
                    + "短期记忆 \\d{1,10} 条，召回结果 \\d{1,10} 条。敏感正文已省略。$"
    );
    private static final Pattern RESEARCH_SUMMARY = Pattern.compile(
            "^(?:已完成授权网页检索|授权网页检索失败)：获得 \\d{1,10} 个可追溯来源；来源域名："
                    + "(?:无可展示来源域名|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?"
                    + "(?:、[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?){0,7})。"
                    + "外部网页正文与搜索片段已省略。$"
    );
    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$"
    );
    private static final Pattern TOOL_RESULT_REFERENCE = Pattern.compile("^tool-result://[0-9]{1,20}$");
    private static final Pattern INTERNAL_REFERENCE = Pattern.compile(
            "^(?:cli-command|release-check|agent-result|pending-operation|agent-task)://[0-9]{1,20}$"
    );
    private static final Pattern PROJECT_REFERENCE = Pattern.compile("^project://[0-9]{1,20}(?:/overview)?$");
    private static final Pattern WORKSPACE_REFERENCE = Pattern.compile(
            "^workspace://[0-9]{1,20}(?:/[A-Za-z0-9._/-]{1,360})?$"
    );
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|cookie)\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)\\b(authorization)\\s*[:=]\\s*(?:[A-Za-z][A-Za-z0-9_-]*\\s+)?([^\\s,;]+)"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern KNOWN_TOKEN = Pattern.compile("\\b(?:sk-|ghp_)[A-Za-z0-9_-]{12,}\\b");
    private static final String CRITERION_NOTE = "该标准仅由脱敏摘要和可追溯来源引用支持。";

    private AgentToolOutputSanitizer() {
    }

    public static Map<String, Object> sanitize(String toolRef, Map<String, Object> raw) {
        return sanitize(toolRef, raw, List.of());
    }

    public static Map<String, Object> sanitize(
            String toolRef, Map<String, Object> raw, List<?> completionCriteria
    ) {
        return sanitize(toolRef, raw, completionCriteria, ConsumedSensitivity.NONE);
    }

    public static Map<String, Object> sanitize(
            String toolRef,
            Map<String, Object> raw,
            List<?> completionCriteria,
            ConsumedSensitivity consumedSensitivity
    ) {
        Map<String, Object> source = raw == null ? Map.of() : raw;
        boolean directSensitive = SENSITIVE_TOOLS.contains(toolRef);
        ConsumedSensitivity trustedSensitivity = consumedSensitivity == null
                ? ConsumedSensitivity.NONE : consumedSensitivity;
        boolean derivedSensitive = "analysis.synthesize".equals(toolRef)
                && trustedSensitivity != ConsumedSensitivity.NONE;
        if (directSensitive || derivedSensitive) {
            return rebuildSensitiveResult(
                    toolRef, source, completionCriteria,
                    derivedSensitive ? trustedSensitivity : ConsumedSensitivity.NONE
            );
        }

        Map<String, Object> result = sanitizeMap(source, false);
        result.put("summary", maskSecrets(String.valueOf(
                result.getOrDefault("summary", "工具执行结果已脱敏。")
        )));
        return result;
    }

    /** Classifies the exact immutable Artifact list supplied to a synthesis step by the controller. */
    public static ConsumedSensitivity classifyConsumedArtifacts(List<? extends Map<String, ?>> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return ConsumedSensitivity.NONE;
        boolean external = false;
        for (Map<String, ?> artifact : artifacts) {
            if (artifact == null) continue;
            Object rawMetadata = artifact.get("metadata");
            if (!(rawMetadata instanceof Map<?, ?> metadata)) continue;
            String sensitivity = upperString(metadata.get("sensitivity"));
            String toolRef = metadata.get("toolRef") instanceof String text ? text : "";
            if ("PERSONAL".equals(sensitivity) || "memory.snapshot".equals(toolRef)) {
                return ConsumedSensitivity.PERSONAL;
            }
            if ("EXTERNAL_CONTENT".equals(sensitivity) || "research.search".equals(toolRef)) {
                external = true;
            }
        }
        return external ? ConsumedSensitivity.EXTERNAL_CONTENT : ConsumedSensitivity.NONE;
    }

    /** Reconstructs the complete persisted object from explicitly allowed fields and values. */
    private static Map<String, Object> rebuildSensitiveResult(
            String toolRef,
            Map<String, Object> source,
            List<?> completionCriteria,
            ConsumedSensitivity derivedSensitivity
    ) {
        boolean derived = derivedSensitivity != ConsumedSensitivity.NONE;
        Map<String, Object> result = new LinkedHashMap<>();
        String status = allowedString(source.get("status"), RESULT_STATUSES, "ERROR");
        List<String> evidenceRefs = sensitiveReferences(source.get("evidenceRefs"), derived);
        String dataRef = toolResultReference(source.get("dataRef"));
        Set<String> criterionRefs = new LinkedHashSet<>(evidenceRefs);
        if (dataRef != null) criterionRefs.add(dataRef);

        String summary;
        String sensitivity;
        String contentTrust;
        if (derived) {
            summary = "已形成结构化执行结论；敏感上游正文已省略。";
            sensitivity = derivedSensitivity.name();
            contentTrust = "TRUSTED";
        } else {
            Object rawSummary = source.get("summary");
            summary = isStructuredSummary(toolRef, rawSummary)
                    ? (String) rawSummary : fallbackSummary(toolRef, evidenceRefs.size());
            sensitivity = "memory.snapshot".equals(toolRef) ? "PERSONAL" : "EXTERNAL_CONTENT";
            contentTrust = "research.search".equals(toolRef) ? "UNTRUSTED_EXTERNAL" : "TRUSTED";
        }

        String errorCode = "ERROR".equals(status)
                ? (RESULT_STATUSES.contains(stringValue(source.get("status")))
                        ? "SENSITIVE_TOOL_ERROR" : "SENSITIVE_TOOL_RESULT_INVALID")
                : null;

        // This is the complete whitelist for sensitive ToolResult persistence.
        result.put("status", status);
        result.put("summary", summary);
        result.put("dataRef", dataRef);
        result.put("evidenceRefs", evidenceRefs);
        result.put("sideEffect", "NONE");
        result.put("retryable", Boolean.TRUE.equals(source.get("retryable")));
        result.put("errorCode", "ERROR".equals(status) ? errorCode : null);
        result.put("contentTrust", contentTrust);
        result.put("redacted", true);
        result.put("sensitivity", sensitivity);
        result.put("securityFlags", safeSecurityFlags(source.get("securityFlags")));
        result.put("criterionEvidence", safeCriterionEvidence(
                source.get("criterionEvidence"), completionCriteria, criterionRefs
        ));
        result.put("externalStateHashes", Map.of());
        result.put("usage", safeUsage(source.get("usage")));
        return result;
    }

    private static boolean isStructuredSummary(String toolRef, Object value) {
        if (!(value instanceof String summary) || summary.length() > 1_000) return false;
        return "memory.snapshot".equals(toolRef)
                ? MEMORY_SUMMARY.matcher(summary).matches()
                : RESEARCH_SUMMARY.matcher(summary).matches();
    }

    private static List<Map<String, Object>> safeCriterionEvidence(
            Object value, List<?> completionCriteria, Set<String> allowedEvidenceRefs
    ) {
        Map<String, String> allowedCriteria = new LinkedHashMap<>();
        if (completionCriteria != null) {
            for (Object raw : completionCriteria) {
                String criterion = criterionText(raw);
                if (!criterion.isBlank()) allowedCriteria.putIfAbsent(normalizeCriterion(criterion), criterion);
                if (allowedCriteria.size() >= 20) break;
            }
        }
        if (allowedCriteria.isEmpty() || !(value instanceof List<?> mappings)) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> mappedCriteria = new LinkedHashSet<>();
        for (Object item : mappings) {
            if (!(item instanceof Map<?, ?> mapping)) continue;
            String requested = mapping.get("criterion") instanceof String text ? text : "";
            String normalized = normalizeCriterion(requested);
            String criterion = allowedCriteria.get(normalized);
            if (criterion == null || !mappedCriteria.add(normalized)) continue;

            String status = allowedString(mapping.get("status"), CRITERION_STATUSES, "UNVERIFIED");
            List<String> refs = sensitiveReferences(mapping.get("evidenceRefs"), false).stream()
                    .filter(allowedEvidenceRefs::contains)
                    .limit(50)
                    .toList();
            if ("COMPLETE".equals(status) && refs.isEmpty()) status = "UNVERIFIED";

            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("criterion", criterion);
            safe.put("status", status);
            safe.put("evidenceRefs", refs);
            safe.put("note", CRITERION_NOTE);
            result.add(safe);
            if (result.size() >= 20) break;
        }
        return result;
    }

    private static String criterionText(Object value) {
        if (value instanceof String text) {
            String stripped = text.strip();
            return stripped.substring(0, Math.min(stripped.length(), 1_000));
        }
        if (!(value instanceof Map<?, ?> mapping)) return "";
        for (String key : List.of("criterion", "title", "description", "text")) {
            Object candidate = mapping.get(key);
            if (candidate instanceof String text && !text.isBlank()) {
                String stripped = text.strip();
                return stripped.substring(0, Math.min(stripped.length(), 1_000));
            }
        }
        return "";
    }

    private static String normalizeCriterion(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static List<String> safeSecurityFlags(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        for (Object item : source) {
            if (item instanceof String flag && SECURITY_FLAGS.contains(flag)) flags.add(flag);
            if (flags.size() >= 20) break;
        }
        return List.copyOf(flags);
    }

    private static Map<String, Object> safeUsage(Object value) {
        Map<?, ?> source = value instanceof Map<?, ?> mapping ? mapping : Map.of();
        return Map.of(
                "modelTokens", nonNegativeLong(source.get("modelTokens")),
                "costMicros", nonNegativeLong(source.get("costMicros"))
        );
    }

    private static long nonNegativeLong(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private static String allowedString(Object value, Set<String> allowed, String fallback) {
        String candidate = stringValue(value);
        return allowed.contains(candidate) ? candidate : fallback;
    }

    private static String stringValue(Object value) {
        return upperString(value);
    }

    private static String upperString(Object value) {
        return value instanceof String text ? text.toUpperCase(Locale.ROOT) : "";
    }

    private static List<String> sensitiveReferences(Object value, boolean allowDerivedInternalRefs) {
        if (!(value instanceof List<?> source)) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : source) {
            String safe = sensitiveReference(item, allowDerivedInternalRefs);
            if (safe != null) result.add(safe);
            if (result.size() >= 50) break;
        }
        return List.copyOf(result);
    }

    private static String toolResultReference(Object value) {
        return value instanceof String text && TOOL_RESULT_REFERENCE.matcher(text).matches() ? text : null;
    }

    private static String sensitiveReference(Object value, boolean allowDerivedInternalRefs) {
        if (!(value instanceof String text) || text.length() > 512) return null;
        if (TOOL_RESULT_REFERENCE.matcher(text).matches()) return text;
        if (allowDerivedInternalRefs && (INTERNAL_REFERENCE.matcher(text).matches()
                || PROJECT_REFERENCE.matcher(text).matches()
                || WORKSPACE_REFERENCE.matcher(text).matches())) return text;
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || !HOST.matcher(host).matches()) return null;
            // External references are stored at origin granularity so paths cannot become a text smuggling channel.
            return new URI(scheme, null, host, uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException exc) {
            return null;
        }
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> source, boolean sensitive) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = key == null ? "" : key.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
            if (sensitive && RAW_KEYS.contains(normalized)) return;
            result.put(key, sanitizeValue(key, value, sensitive));
        });
        return result;
    }

    private static Object sanitizeValue(String key, Object value, boolean sensitive) {
        if (value instanceof String text) {
            String masked = maskSecrets(text);
            if ("evidenceRefs".equals(key) || "contentRef".equals(key) || "dataRef".equals(key)) {
                return sanitizeReference(masked);
            }
            return masked;
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            source.forEach((nestedKey, nestedValue) -> {
                String name = String.valueOf(nestedKey);
                String normalized = name.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
                if (sensitive && RAW_KEYS.contains(normalized)) return;
                mapped.put(name, sanitizeValue(name, nestedValue, sensitive));
            });
            return mapped;
        }
        if (value instanceof List<?> source) {
            List<Object> mapped = new ArrayList<>();
            for (Object item : source) {
                Object sanitized = sanitizeValue(key, item, sensitive);
                mapped.add(("evidenceRefs".equals(key) && sanitized instanceof String text)
                        ? sanitizeReference(text) : sanitized);
            }
            return mapped;
        }
        return value;
    }

    private static String fallbackSummary(String toolRef, int evidenceCount) {
        if ("memory.snapshot".equals(toolRef)) {
            return "已读取个人记忆索引；个人长期记忆、短期记忆和召回正文已省略。";
        }
        return "已完成授权网页检索，保留 " + evidenceCount + " 个脱敏来源引用；外部网页正文与搜索片段已省略。";
    }

    private static String sanitizeReference(String value) {
        if (!value.startsWith("http://") && !value.startsWith("https://")) return value;
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null) return "external-source://redacted";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (URISyntaxException exc) {
            return "external-source://redacted";
        }
    }

    private static String maskSecrets(String value) {
        String masked = KEY_VALUE_SECRET.matcher(value).replaceAll("$1=***");
        masked = AUTHORIZATION.matcher(masked).replaceAll("$1=***");
        masked = BEARER.matcher(masked).replaceAll("Bearer ***");
        return KNOWN_TOKEN.matcher(masked).replaceAll("***");
    }
}
