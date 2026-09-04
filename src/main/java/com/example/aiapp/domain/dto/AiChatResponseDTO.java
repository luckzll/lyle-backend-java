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
        Map<String, Object> review,
        List<Map<String, Object>> usedMemories,
        Map<String, Object> memoryExclusions,
        String memoryExplanationVersion,
        String debugVersion,
        List<String> knowledgeBaseIds,
        Map<String, Object> retrievalScope,
        Boolean debugTruncated
) {
    public AiChatResponseDTO(
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
        this(
                answer, sources, usage, agentTrace, toolResults, candidateMemories, candidateTasks,
                candidateGoals, candidateDecisions, candidateOpenQuestions, candidatePlans,
                executionSuggestions, projectContext, review, List.of(), Map.of(), "v1",
                null, List.of(), Map.of(), false
        );
    }

    /** Backwards-compatible constructor for callers using the pre-debug response contract. */
    public AiChatResponseDTO(
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
            Map<String, Object> review,
            List<Map<String, Object>> usedMemories,
            Map<String, Object> memoryExclusions,
            String memoryExplanationVersion
    ) {
        this(
                answer, sources, usage, agentTrace, toolResults, candidateMemories, candidateTasks,
                candidateGoals, candidateDecisions, candidateOpenQuestions, candidatePlans,
                executionSuggestions, projectContext, review, usedMemories, memoryExclusions,
                memoryExplanationVersion, null, List.of(), Map.of(), false
        );
    }
}
