package com.example.aiapp.domain.dto;

import java.util.List;
import java.util.Map;

public record AiIngestResponseDTO(
        String taskId,
        String status,
        String message,
        List<Map<String, Object>> candidateMemories
) {
}
