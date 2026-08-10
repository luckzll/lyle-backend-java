package com.example.aiapp.domain.vo;

import com.example.aiapp.domain.dto.UsageDTO;

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
        List<Map<String, Object>> candidateGoals,
        List<Map<String, Object>> candidateDecisions,
        List<Map<String, Object>> candidateOpenQuestions,
        List<Map<String, Object>> candidatePlans,
        List<Map<String, Object>> executionSuggestions,
        Map<String, Object> projectContext,
        Map<String, Object> review,
        UsageDTO usage,
        List<Map<String, Object>> usedMemories,
        Map<String, Object> memoryExclusions,
        String memoryExplanationVersion
) {
    public ChatResponseVO(
            String conversationId,
            String answer,
            List<SourceVO> sources,
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
        this(
                conversationId,
                answer,
                sources,
                agentTrace,
                toolResults,
                candidateMemories,
                candidateTasks,
                candidateGoals,
                candidateDecisions,
                candidateOpenQuestions,
                candidatePlans,
                executionSuggestions,
                projectContext,
                review,
                new UsageDTO(0, 0),
                List.of(),
                Map.of(),
                "v1"
        );
    }
}
