package com.example.aiapp.domain.vo;

import java.util.List;
import java.util.Map;

public record ChatResponseVO(
        String conversationId,
        String answer,
        List<SourceVO> sources,
        List<Map<String, Object>> agentTrace,
        List<Map<String, Object>> toolResults,
        List<Map<String, Object>> candidateMemories,
        List<Map<String, Object>> candidateTasks,
        Map<String, Object> review
) {
}
