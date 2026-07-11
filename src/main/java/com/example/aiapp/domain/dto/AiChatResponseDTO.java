package com.example.aiapp.domain.dto;

import com.example.aiapp.domain.vo.SourceVO;

import java.util.List;
import java.util.Map;

public record AiChatResponseDTO(
        String answer,
        List<SourceVO> sources,
        UsageDTO usage,
        List<Map<String, Object>> agentTrace,
        List<Map<String, Object>> toolResults,
        List<Map<String, Object>> candidateMemories,
        List<Map<String, Object>> candidateTasks,
        List<Map<String, Object>> candidateGoals,
        List<Map<String, Object>> candidateDecisions,
        List<Map<String, Object>> candidateOpenQuestions,
        List<Map<String, Object>> candidatePlans,
        List<Map<String, Object>> executionSuggestions,
        Map<String, Object> projectContext,
        Map<String, Object> review
) {
}
