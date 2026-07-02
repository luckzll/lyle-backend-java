package com.example.aiapp.domain.dto;

public record AiIngestRequestDTO(
        String fileId,
        String knowledgeBaseId,
        String storagePath,
        String userId
) {
}
