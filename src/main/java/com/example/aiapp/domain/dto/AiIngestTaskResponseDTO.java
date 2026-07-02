package com.example.aiapp.domain.dto;

public record AiIngestTaskResponseDTO(
        String taskId,
        String fileId,
        String knowledgeBaseId,
        String status,
        String message
) {
}
