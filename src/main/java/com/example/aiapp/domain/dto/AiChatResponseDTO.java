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
        Map<String, Object> review
) {
}
